// All user-facing text lives here (English only, no i18n framework).
export const strings = {
  appName: 'Dockline',
  sidebarLabel: 'Main menu',
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
  }
} as const
