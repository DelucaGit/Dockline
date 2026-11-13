import { Button } from '@/components/ui/button'
import { strings } from '@/strings'

export function ViewLoading() {
  return (
    <p role="status" className="mt-8 text-center text-muted-foreground">
      {strings.view.loading}
    </p>
  )
}

export function ViewEmpty({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="mt-8 text-center">
      <p className="text-lg font-medium">{title}</p>
      {hint && <p className="mt-1 text-muted-foreground">{hint}</p>}
    </div>
  )
}

/** Generic failure with the server's own explanation. Specific error states come per view. */
export function ViewError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div role="alert" className="mt-6 rounded-md border border-border p-4">
      <p className="text-red-600 dark:text-red-400">{message}</p>
      <Button className="mt-3" variant="ghost" onClick={onRetry}>
        {strings.view.retry}
      </Button>
    </div>
  )
}
