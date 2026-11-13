import type { ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { strings } from '@/strings'

export const backendStateKey = ['backend-state'] as const

/**
 * Shows a loading screen while the backend starts and an error screen if it
 * fails. Renders `children` with the backend's base URL once it is ready.
 */
export function BackendGate({ children }: { children: (baseUrl: string) => ReactNode }) {
  const queryClient = useQueryClient()
  const { data, isError } = useQuery({
    queryKey: backendStateKey,
    queryFn: () => window.dockline.getBackendState(),
    refetchInterval: (query) =>
      query.state.data?.status === 'ready' || query.state.data?.status === 'error' ? false : 500
  })

  const retry = async () => {
    queryClient.setQueryData(backendStateKey, { status: 'starting' })
    queryClient.setQueryData(backendStateKey, await window.dockline.retryBackend())
  }

  if (isError) return <ErrorScreen message={strings.backend.unreachable} onRetry={retry} />
  if (!data || data.status === 'starting') return <LoadingScreen />
  if (data.status === 'error') return <ErrorScreen message={data.message} onRetry={retry} />
  return <>{children(data.baseUrl)}</>
}

function LoadingScreen() {
  return (
    <div role="status" className="flex h-screen flex-col items-center justify-center gap-3">
      <div
        aria-hidden
        className="size-8 animate-spin rounded-full border-4 border-muted border-t-primary"
      />
      <p className="text-lg font-medium">{strings.backend.loadingTitle}</p>
      <p className="text-sm text-muted-foreground">{strings.backend.loadingHint}</p>
    </div>
  )
}

function ErrorScreen({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div role="alert" className="flex h-screen flex-col items-center justify-center gap-3 p-8">
      <h1 className="text-xl font-semibold">{strings.backend.errorTitle}</h1>
      <pre className="max-h-64 max-w-2xl overflow-auto whitespace-pre-wrap rounded-md bg-muted p-3 text-sm">
        {message}
      </pre>
      <Button onClick={onRetry}>{strings.backend.retry}</Button>
    </div>
  )
}
