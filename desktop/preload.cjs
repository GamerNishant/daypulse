// Narrow, typed bridge between the web UI and the Electron main process (see web/src/lib/platform.ts).
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('daypulse', {
  platform: 'desktop',
  reportState: (state) => ipcRenderer.send('state', state),
  onCommand: (cb) => {
    ipcRenderer.removeAllListeners('command')
    ipcRenderer.on('command', (_e, cmd) => cb(cmd))
    ipcRenderer.send('ready')
  },
  hide: () => ipcRenderer.send('hide'),
  getAutoStart: () => ipcRenderer.invoke('autostart:get'),
  setAutoStart: (on) => ipcRenderer.send('autostart:set', !!on),
})
