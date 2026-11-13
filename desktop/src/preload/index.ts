import { contextBridge, ipcRenderer } from 'electron'
import { IPC, type DocklineApi } from '../shared/backend'

const api: DocklineApi = {
  getBackendState: () => ipcRenderer.invoke(IPC.getBackendState),
  retryBackend: () => ipcRenderer.invoke(IPC.retryBackend)
}

contextBridge.exposeInMainWorld('dockline', api)
