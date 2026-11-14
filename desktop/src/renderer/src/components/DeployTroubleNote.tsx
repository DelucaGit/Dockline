import type { DeployTrouble } from '@/lib/useSiteDeploys'
import { strings } from '@/strings'

/**
 * A small inline note saying why the deploy status is not fresh: rate limited (with a countdown) or another
 * failure (with the backend's explanation). A note, never an error state: the rest of the view keeps working.
 */
export function DeployTroubleNote({ trouble, className = '' }: { trouble: DeployTrouble | null; className?: string }) {
  if (!trouble) return null
  const t = strings.deploy
  return (
    <p role="status" data-testid="deploy-trouble" className={`text-xs text-amber-700 dark:text-amber-400 ${className}`}>
      {trouble.kind === 'RATE_LIMITED' ? (
        t.rateLimited.replace('{seconds}', String(trouble.secondsLeft))
      ) : (
        <>
          {t.updateFailed}
          {trouble.message && <span className="text-muted-foreground"> {trouble.message}</span>}
        </>
      )}
    </p>
  )
}
