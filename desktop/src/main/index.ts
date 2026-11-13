import { join } from 'node:path'
import { app, BrowserWindow, ipcMain, nativeTheme, session } from 'electron'
import { IPC } from '../shared/backend'
import { BackendProcess } from './backend'

// Follow the Windows light/dark setting (Electron's default; explicit for clarity).
nativeTheme.themeSource = 'system'

const backend = new BackendProcess(app.getAppPath())

// Never leave a Java process behind: quit, normal exit and crashes all stop it.
app.on('before-quit', () => backend.stop())
process.on('exit', () => backend.stop())
process.on('uncaughtException', (err) => {
  backend.stop()
  console.error(err)
  app.exit(1)
})

function createWindow(): void {
  const win = new BrowserWindow({
    width: 1100,
    height: 720,
    minWidth: 800,
    minHeight: 500,
    title: 'Dockline',
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      preload: join(__dirname, '../preload/index.cjs')
    }
  })
  win.once('ready-to-show', () => win.show())

  if (process.env['ELECTRON_RENDERER_URL']) {
    void win.loadURL(process.env['ELECTRON_RENDERER_URL'])
  } else {
    void win.loadFile(join(__dirname, '../renderer/index.html'))
  }
}

void app.whenReady().then(() => {
  ipcMain.handle(IPC.getBackendState, () => backend.getState())
  ipcMain.handle(IPC.retryBackend, () => backend.start())

  // The renderer (a different origin) fetches the backend directly; allow that for 127.0.0.1 only.
  session.defaultSession.webRequest.onHeadersReceived(
    { urls: ['http://127.0.0.1/*'] },
    (details, callback) => {
      callback({
        responseHeaders: { ...details.responseHeaders, 'Access-Control-Allow-Origin': ['*'] }
      })
    }
  )

  createWindow()
  void backend.start()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
