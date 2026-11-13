// Shared between the Electron main process, the preload script and the renderer.

export type BackendState =
  | { status: 'starting' }
  | { status: 'ready'; baseUrl: string }
  | { status: 'error'; message: string }

/** The API the preload script exposes to the renderer as `window.dockline`. */
export interface DocklineApi {
  /** Current state of the backend child process. */
  getBackendState(): Promise<BackendState>
  /** Try to start the backend again after a failure. */
  retryBackend(): Promise<BackendState>
  /** Open an https address in the user's default browser. Rejects anything that is not https. */
  openExternal(url: string): Promise<void>
}

export const IPC = {
  getBackendState: 'backend:get-state',
  retryBackend: 'backend:retry',
  openExternal: 'app:open-external'
} as const
