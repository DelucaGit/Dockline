import type { ReactNode } from 'react'
import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { cn } from '@/lib/utils'
import { strings } from '@/strings'
import { routes } from '@/routes'
import { LogPage } from '@/pages/LogPage'
import { OverviewPage } from '@/pages/OverviewPage'
import { Placeholder } from '@/pages/Placeholder'
import { ReposPage } from '@/pages/ReposPage'
import { DomainsPage } from '@/pages/DomainsPage'
import { SitesPage } from '@/pages/SitesPage'
import { SiteDetailPage } from '@/pages/SiteDetailPage'
import { SettingsPage } from '@/pages/SettingsPage'
import { Connections } from '@/components/Connections'

function pageElement(path: string, page: { title: string; empty: string }, baseUrl?: string) {
  if (baseUrl && path === '/overview') return <OverviewPage baseUrl={baseUrl} />
  if (baseUrl && path === '/log') return <LogPage baseUrl={baseUrl} />
  if (baseUrl && path === '/repos') return <ReposPage baseUrl={baseUrl} />
  if (baseUrl && path === '/sites') return <SitesPage baseUrl={baseUrl} />
  if (baseUrl && path === '/domains') return <DomainsPage baseUrl={baseUrl} />
  if (baseUrl && path === '/settings') return <SettingsPage baseUrl={baseUrl} />
  return <Placeholder {...page} />
}

export function App({ footer, baseUrl }: { footer?: ReactNode; baseUrl?: string }) {
  return (
    <div className="flex h-screen">
      <nav
        aria-label={strings.sidebarLabel}
        className="flex w-56 shrink-0 flex-col gap-1 border-r border-border bg-muted p-3"
      >
        <div className="px-3 pb-3 pt-1 text-lg font-semibold">{strings.appName}</div>
        {routes.map(({ path, label, icon: Icon }) => (
          <NavLink
            key={path}
            to={path}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-2 rounded-md px-3 py-2 text-sm hover:bg-accent',
                isActive && 'bg-accent font-medium text-accent-foreground'
              )
            }
          >
            <Icon className="size-4" aria-hidden />
            {label}
          </NavLink>
        ))}
        <div className="mt-auto">
          {baseUrl && <Connections baseUrl={baseUrl} />}
          {footer}
        </div>
      </nav>
      <main className="flex-1 overflow-auto p-6">
        <Routes>
          <Route path="/" element={<Navigate to={routes[0].path} replace />} />
          {routes.map(({ path, page }) => (
            <Route key={path} path={path} element={pageElement(path, page, baseUrl)} />
          ))}
          {/* Not in `routes`, so it gets no sidebar entry; "Sites" stays highlighted because its link matches by prefix. */}
          {baseUrl && <Route path="/sites/:domain" element={<SiteDetailPage baseUrl={baseUrl} />} />}
          <Route path="*" element={<Navigate to={routes[0].path} replace />} />
        </Routes>
      </main>
    </div>
  )
}
