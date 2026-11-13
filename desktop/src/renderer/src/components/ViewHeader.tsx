import { RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { strings } from '@/strings'

interface ViewHeaderProps {
  title: string
  onRefresh: () => void
  isFetching: boolean
  /** When the data was fetched (ISO); omitted until the first answer. */
  fetchedAt?: string
}

/** Title, "Last updated" and Refresh: the top of every data view (Repos, Sites, Domains). */
export function ViewHeader({ title, onRefresh, isFetching, fetchedAt }: ViewHeaderProps) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <h1 className="mr-auto text-2xl font-semibold">{title}</h1>
      {fetchedAt && (
        <p className="text-sm text-muted-foreground">
          {strings.view.lastUpdated} <time dateTime={fetchedAt}>{new Date(fetchedAt).toLocaleTimeString()}</time>
        </p>
      )}
      <Button variant="ghost" onClick={onRefresh} disabled={isFetching} aria-busy={isFetching}>
        <RefreshCw className={cn('size-4', isFetching && 'animate-spin')} aria-hidden />
        {strings.view.refresh}
      </Button>
    </div>
  )
}
