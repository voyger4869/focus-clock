const { contextBridge, ipcRenderer } = require('electron');

// 暴露给渲染进程的安全桥（contextIsolation 开启下通过 contextBridge）
contextBridge.exposeInMainWorld('electronAPI', {
  // 弹系统通知（专注/休息结束）
  notify: (title, body) => {
    try { ipcRenderer.send('notify', { title, body }); } catch (e) {}
  },
});
