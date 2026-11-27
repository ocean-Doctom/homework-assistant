import { createWorkBuddyCloud } from "@tencent-ai/workbuddy-cloud-sdk";
import { publicConfig } from "./cloudConfig.js";

const SESSION_KEY = `workbuddy-cloud.session.${publicConfig.publishableKey}`;

export const cloud = createWorkBuddyCloud({
  endpoint: publicConfig.endpoint,
  oauthRelayBaseUrl: publicConfig.oauthRelayBaseUrl,
  publishableKey: publicConfig.publishableKey,
});

export let startupError = "";

export function hasCloudSession() {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    return Boolean(raw && raw !== "null");
  } catch {
    return false;
  }
}

export async function restoreAuth() {
  const url = new URL(location.href);
  if (!url.searchParams.has("code") && !url.searchParams.has("error")) return;
  if (url.searchParams.get("provider") !== "workbuddy") {
    startupError = "授权回调缺少有效的登录方式，请重新登录。";
    return;
  }
  const fn = cloud.auth.handleWorkBuddyCallback;
  if (typeof fn !== "function") {
    startupError = "SDK 缺少该登录能力。";
    return;
  }
  const { data: session, error } = await fn.call(cloud.auth);
  if (error) {
    startupError = error.message || "登录失败";
    return;
  }
  if (session) {
    for (const key of ["code", "state", "provider"]) url.searchParams.delete(key);
    history.replaceState(null, "", `${url.pathname}${url.search}${url.hash}`);
  }
}

export async function startWorkBuddyLogin() {
  const back = new URL(location.href);
  back.pathname = "/";
  for (const key of ["code", "state", "provider"]) back.searchParams.delete(key);
  const { data, error } = await cloud.auth.signInWithOAuth({
    provider: "workbuddy",
    redirectTo: back.toString(),
  });
  if (error) throw new Error(error.message || "登录失败");
  location.assign(data.url);
}
