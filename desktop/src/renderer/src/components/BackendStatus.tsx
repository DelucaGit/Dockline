import { useQuery } from '@tanstack/react-query'
import { fetchHealth } from '@/api/health'
import { strings } from '@/strings'

/** Small indicator at the bottom of the sidebar, driven by the backend's health endpoint. */
export function BackendStatus({ baseUrl }: { baseUrl: string }) {
  const { data, isPending } = useQuery({
    queryKey: ['backend-health', baseUrl],
    queryFn: () => fetchHealth(baseUrl),
    refetchInterval: 5000
  })

  const connected = data?.status === 'UP'
  const text = isPending
    ? strings.backend.checking
    : connected
      ? strings.backend.connected
      : strings.backend.disconnected

  return (
    <div role="status" className="flex items-center gap-2 px-3 py-1.5 text-sm">
      <span
        aria-hidden
        className={`size-2.5 shrink-0 rounded-full ${connected ? 'bg-green-500' : isPending ? 'bg-muted-foreground' : 'bg-red-500'}`}
      />
      {text}
    </div>
  )
}
