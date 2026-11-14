import type { SiteDeploy, SiteDeployStatus } from '@/api/deploys'
import { formatExactTime, formatRelativeTime } from '@/lib/relativeTime'
import { strings } from '@/strings'

const BADGE: Record<Exclude<SiteDeployStatus, 'NONE'>, { label: string; className: string }> = {
  SUCCEEDED: {
    label: strings.deploy.succeeded,
    className: 'border-green-600/40 bg-green-500/10 text-green-700 dark:text-green-400'
  },
  FAILED: { label: strings.deploy.failed, className: 'border-red-600/40 bg-red-500/10 text-red-700 dark:text-red-400' },
  IN_PROGRESS: {
    label: strings.deploy.inProgress,
    className: 'border-amber-600/40 bg-amber-500/10 text-amber-700 dark:text-amber-400'
  },
  UNKNOWN: { label: strings.deploy.unknown, className: 'border-border bg-muted text-muted-foreground' }
}

/**
 * The latest deploy of one site: status badge, time, short commit and source. Without a deploy (not a Node.js
 * website, never built, or no entry at all) it says so in muted text.
 */
export function DeployStatus({ deploy, className = '' }: { deploy?: SiteDeploy | null; className?: string }) {
  const t = strings.deploy
  if (!deploy || deploy.status === 'NONE') {
    return (
      <p data-testid="deploy-status" className={`text-xs text-muted-foreground ${className}`}>
        {t.none}
      </p>
    )
  }
  const badge = BADGE[deploy.status]
  return (
    <p
      data-testid="deploy-status"
      className={`flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground ${className}`}
    >
      <span data-testid="deploy-badge" className={`rounded border px-1.5 py-0.5 font-medium ${badge.className}`}>
        {badge.label}
      </span>
      {deploy.at && (
        <time dateTime={deploy.at} title={formatExactTime(deploy.at)}>
          {formatRelativeTime(deploy.at)}
        </time>
      )}
      {deploy.commit && (
        <code data-testid="deploy-commit" title={deploy.commit.message ?? undefined}>
          {deploy.commit.sha.slice(0, 7)}
        </code>
      )}
      {deploy.source === 'HOSTINGER' && <span>{t.sourceHostinger}</span>}
    </p>
  )
}
