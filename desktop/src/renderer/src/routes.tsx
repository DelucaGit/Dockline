import { Eye, Globe, GitBranch, Globe2, ScrollText, Settings, type LucideIcon } from 'lucide-react'
import { strings } from '@/strings'

export interface AppRoute {
  path: string
  label: string
  page: { title: string; empty: string }
  icon: LucideIcon
}

// The v1 menu, in display order. The first entry is the start page.
export const routes: AppRoute[] = [
  { path: '/overview', label: strings.nav.overview, page: strings.pages.overview, icon: Eye },
  { path: '/sites', label: strings.nav.sites, page: strings.pages.sites, icon: Globe },
  { path: '/repos', label: strings.nav.repos, page: strings.pages.repos, icon: GitBranch },
  { path: '/domains', label: strings.nav.domains, page: strings.pages.domains, icon: Globe2 },
  { path: '/log', label: strings.nav.log, page: strings.pages.log, icon: ScrollText },
  { path: '/settings', label: strings.nav.settings, page: strings.pages.settings, icon: Settings }
]
