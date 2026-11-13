/// <reference types="vite/client" />
import type { DocklineApi } from '../../shared/backend'

declare global {
  interface Window {
    /** Exposed by the preload script. */
    dockline: DocklineApi
  }
}
