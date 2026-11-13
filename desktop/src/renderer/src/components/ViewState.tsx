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

/** One width for every card in the app's views (Settings sections, error cards). */
export const CARD = 'max-w-xl rounded-md border border-border p-4'

/** Generic failure: one heading and the server's own explanation, once. Specific error states come per view. */
export function ViewError({ title, message, onRetry }: { title: string; message: string; onRetry: () => void }) {
  return (
    <div role="alert" className={`mt-6 ${CARD}`}>
      <h2 className="font-semibold text-red-600 dark:text-red-400">{title}</h2>
      <p className="mt-1 text-sm text-muted-foreground">{message}</p>
      <Button className="mt-3" variant="outline" onClick={onRetry}>
        {strings.view.retry}
      </Button>
    </div>
  )
}
