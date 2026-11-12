const { app, BrowserWindow, shell, Tray, Menu, Notification, ipcMain } = require('electron');
const path = require('path');

let mainWindow = null;
let tray = null;
let isQuitting = false;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 560,
    height: 900,
    minWidth: 400,
    minHeight: 600,
    autoHideMenuBar: true,
    title: '专注时钟',
    backgroundColor: '#1a1b26',
    icon: path.join(__dirname, 'icon-512.png'),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.js'),
    },
  });

  mainWindow.loadFile(path.join(__dirname, 'index.html'));

  // 外部链接用系统浏览器打开
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  // 关闭窗口时最小化到托盘，而非退出（除非真正退出）
  mainWindow.on('close', (e) => {
    if (!isQuitting) {
      e.preventDefault();
      mainWindow.hide();
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

function createTray() {
  tray = new Tray(path.join(__dirname, 'icon-192.png'));
  tray.setToolTip('专注时钟');
  const menu = Menu.buildFromTemplate([
    { label: '显示主界面', click: () => showMainWindow() },
    { label: '退出', click: () => { isQuitting = true; app.quit(); } },
  ]);
  tray.setContextMenu(menu);
  tray.on('click', () => showMainWindow());
}

function showMainWindow() {
  if (!mainWindow) { createWindow(); return; }
  mainWindow.show();
  mainWindow.focus();
}

// 渲染进程请求弹系统通知（专注/休息结束）
ipcMain.on('notify', (event, payload) => {
  try {
    const title = payload && payload.title ? payload.title : '专注时钟';
    const body = payload && payload.body ? payload.body : '';
    if (Notification.isSupported()) {
      const n = new Notification({ title, body, icon: path.join(__dirname, 'icon-192.png') });
      n.on('click', () => showMainWindow());
      n.show();
    }
  } catch (e) { /* 忽略通知异常 */ }
});

app.whenReady().then(() => {
  createWindow();
  createTray();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
    else showMainWindow();
  });
});

// 所有窗口关闭时不退出（保留托盘），仅显式退出
app.on('window-all-closed', () => {
  if (process.platform === 'darwin') return;
  // Windows 上保持托盘运行；真正退出由托盘菜单触发
});
