const { app, BrowserWindow, Menu } = require("electron");
const fs = require("fs");
const path = require("path");

const MIN_WIDTH = 420;
const MIN_HEIGHT = 520;

function boundsFile() {
  return path.join(app.getPath("userData"), "window-bounds.json");
}

function loadBounds() {
  try {
    const data = JSON.parse(fs.readFileSync(boundsFile(), "utf8"));
    if (
      Number.isFinite(data.width) &&
      Number.isFinite(data.height) &&
      data.width >= MIN_WIDTH &&
      data.height >= MIN_HEIGHT
    ) {
      return data;
    }
  } catch {
    // 第一次打开没有记录，用默认尺寸。
  }
  return { width: 960, height: 720 };
}

function saveBounds(win) {
  if (win.isDestroyed() || win.isFullScreen() || win.isMinimized()) return;
  const bounds = win.getBounds();
  fs.mkdirSync(path.dirname(boundsFile()), { recursive: true });
  fs.writeFileSync(
    boundsFile(),
    JSON.stringify({
      x: bounds.x,
      y: bounds.y,
      width: bounds.width,
      height: bounds.height,
      maximized: win.isMaximized(),
    }),
  );
}

function adjustZoom(win, delta) {
  const next = Math.min(2, Math.max(0.5, win.webContents.getZoomFactor() + delta));
  win.webContents.setZoomFactor(next);
}

function setAppMenu(win) {
  const menu = Menu.buildFromTemplate([
    {
      label: "视图",
      submenu: [
        {
          label: "全屏",
          accelerator: "F11",
          click: () => win.setFullScreen(!win.isFullScreen()),
        },
        { type: "separator" },
        {
          label: "放大",
          accelerator: "CommandOrControl+=",
          click: () => adjustZoom(win, 0.1),
        },
        {
          label: "缩小",
          accelerator: "CommandOrControl+-",
          click: () => adjustZoom(win, -0.1),
        },
        {
          label: "重置缩放",
          accelerator: "CommandOrControl+0",
          click: () => win.webContents.setZoomFactor(1),
        },
      ],
    },
  ]);
  Menu.setApplicationMenu(menu);
}

function createWindow() {
  const saved = loadBounds();
  const win = new BrowserWindow({
    x: saved.x,
    y: saved.y,
    width: saved.width,
    height: saved.height,
    minWidth: MIN_WIDTH,
    minHeight: MIN_HEIGHT,
    resizable: true,
    maximizable: true,
    fullscreenable: true,
    icon: path.join(__dirname, "icon.png"),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  setAppMenu(win);
  if (saved.maximized) win.maximize();

  const devUrl = process.env.VITE_DEV_SERVER_URL;
  if (devUrl) {
    win.loadURL(devUrl);
  } else {
    win.loadFile(path.join(__dirname, "../dist/index.html"));
  }

  const persist = () => saveBounds(win);
  win.on("resize", persist);
  win.on("move", persist);
  win.on("close", persist);
}

app.whenReady().then(createWindow);

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
