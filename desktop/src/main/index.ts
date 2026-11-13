import { join } from 'node:path'
import { app, BrowserWindow, ipcMain, nativeTheme, session, shell } from 'electron'
import { IPC } from '../shared/backend'
import { BackendProcess } from './backend'
import { isSafeExternalUrl } from './externalUrl'

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
  ipcMain.handle(IPC.openExternal, async (_event, url: unknown) => {
    if (!isSafeExternalUrl(url)) throw new Error('Only https addresses can be opened.')
    if (process.env['DOCKLINE_E2E_NO_BROWSER'] === '1') {
      // Tests and CI: remember the address instead of opening a browser (read with app.evaluate).
      const g = globalThis as { __docklineOpenedUrls?: string[] }
      ;(g.__docklineOpenedUrls ??= []).push(url)
      return
    }
    await shell.openExternal(url)
  })

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
