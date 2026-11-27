import { createReadStream, existsSync, statSync } from "node:fs";
import path from "node:path";
import Fastify from "fastify";

const port = Number(process.env.PORT || 3780);
const host = process.env.HOST || "0.0.0.0";
const publicDir = path.resolve(process.cwd(), "public");
const app = Fastify({ logger: true });

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".json": "application/json; charset=utf-8",
  ".webp": "image/webp",
  ".woff2": "font/woff2",
};

function fileInsidePublic(file) {
  return file === publicDir || file.startsWith(publicDir + path.sep);
}

app.get("/health", async () => ({
  ok: true,
  service: "homework-logistics",
}));

app.setNotFoundHandler((request, reply) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return reply.code(404).send({ error: "没有这个地址" });
  }
  let pathname = "/";
  try {
    pathname = decodeURIComponent(request.url.split("?")[0]);
  } catch {
    pathname = "/";
  }
  const rel = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
  const file = path.resolve(publicDir, rel);
  if (fileInsidePublic(file) && existsSync(file) && statSync(file).isFile()) {
    const type = TYPES[path.extname(file).toLowerCase()] || "application/octet-stream";
    return reply.type(type).send(createReadStream(file));
  }
  const index = path.join(publicDir, "index.html");
  if (!existsSync(index)) return reply.code(404).type("text/plain").send("页面还没构建");
  return reply.type("text/html; charset=utf-8").send(createReadStream(index));
});

await app.listen({ port, host });
