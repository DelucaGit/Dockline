import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { cn } from '@/lib/utils'
import { strings } from '@/strings'
import { routes } from '@/routes'
import { Placeholder } from '@/pages/Placeholder'

export function App() {
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
      </nav>
      <main className="flex-1 overflow-auto p-6">
        <Routes>
          <Route path="/" element={<Navigate to={routes[0].path} replace />} />
          {routes.map(({ path, page }) => (
            <Route key={path} path={path} element={<Placeholder {...page} />} />
          ))}
          <Route path="*" element={<Navigate to={routes[0].path} replace />} />
        </Routes>
      </main>
    </div>
  )
}
