// All user-facing text lives here (English only, no i18n framework).
export const strings = {
  appName: 'Dockline',
  sidebarLabel: 'Main menu',
  backend: {
    loadingTitle: 'Starting Dockline…',
    loadingHint: 'The backend is starting. This can take a few seconds.',
    errorTitle: 'The backend could not be started',
    unreachable: 'The app could not ask the backend for its state.',
    retry: 'Retry',
    checking: 'Checking backend…',
    connected: 'Backend connected',
    disconnected: 'Backend not responding'
  },
  nav: {
    overview: 'Overview',
    sites: 'Sites',
    repos: 'Repos',
    domains: 'Domains',
    log: 'Log',
    settings: 'Settings'
  },
  pages: {
    overview: { title: 'Overview', empty: 'Nothing here yet.' },
    sites: { title: 'Sites', empty: 'Nothing here yet.' },
    repos: { title: 'Repos', empty: 'Nothing here yet.' },
    domains: { title: 'Domains', empty: 'Nothing here yet.' },
    log: { title: 'Log', empty: 'Nothing here yet.' },
    settings: { title: 'Settings', empty: 'Nothing here yet.' }
  },
  log: {
    title: 'Log',
    empty: 'No errors',
    emptyHint: 'Errors and warnings from the backend show up here.',
    loadError: 'The log could not be loaded.',
    refresh: 'Refresh',
    clear: 'Clear',
    clearError: 'The log could not be cleared.',
    copyForAi: 'Copy for AI',
    copied: 'Copied',
    copyFailed: 'Copy failed',
    noDetails: 'No further details.',
    filters: { source: 'Source', level: 'Level', all: 'All' },
    copy: {
      intro: 'Dockline reported this problem. Please help me find the cause and fix it.',
      appVersion: 'App version',
      time: 'Time',
      level: 'Level',
      source: 'Source',
      action: 'Action',
      statusCode: 'Status code',
      none: 'none',
      message: 'Message',
      request: 'Request',
      response: 'Response',
      stackTrace: 'Stack trace'
    }
  }
} as const
