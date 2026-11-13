import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Copy } from 'lucide-react'
import {
  ApiProblem,
  disconnectGithub,
  fetchStatus,
  pollGithubConnect,
  startGithubConnect,
  statusKey,
  type GithubDeviceCode
} from '@/api/github'
import { Button } from '@/components/ui/button'
import { strings } from '@/strings'

const t = strings.settings.github

type Failure = { message: string; setupHelp: boolean }

function failureFrom(error: unknown, fallback: string): Failure {
  if (error instanceof ApiProblem) {
    return { message: error.detail ?? error.title ?? fallback, setupHelp: error.status === 400 }
  }
  return { message: fallback, setupHelp: false }
}

function CopyCodeButton({ code }: { code: string }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')
  async function copy() {
    try {
      await navigator.clipboard.writeText(code)
      setState('copied')
    } catch {
      setState('failed')
    }
    setTimeout(() => setState('idle'), 2000)
  }
  return (
    <Button variant="ghost" size="sm" onClick={copy}>
      {state === 'copied' ? <Check className="size-4" aria-hidden /> : <Copy className="size-4" aria-hidden />}
      {state === 'copied' ? t.codeCopied : state === 'failed' ? t.copyFailed : t.copyCode}
    </Button>
  )
}

function Connecting({ baseUrl, code, onDone, onFailed, onCancel }: {
  baseUrl: string
  code: GithubDeviceCode
  onDone: () => void | Promise<void>
  onFailed: (failure: Failure) => void
  onCancel: () => void
}) {
  const poll = useQuery({
    queryKey: ['github-poll', baseUrl, code.userCode],
    queryFn: () => pollGithubConnect(baseUrl),
    retry: false,
    gcTime: 0,
    // Ask again after the interval the backend gives us (never faster than every second) while PENDING.
    refetchInterval: (query) => {
      const data = query.state.data
      if (query.state.error) return false
      if (data && data.state !== 'PENDING') return false
      return Math.max(1, data?.intervalSeconds ?? code.intervalSeconds) * 1000
    }
  })

  const state = poll.data?.state
  const message = poll.data?.message
  useEffect(() => {
    if (state === 'CONNECTED') onDone()
    else if (state === 'DENIED' || state === 'EXPIRED' || state === 'ERROR' || state === 'NOT_STARTED') {
      onFailed({ message: message ?? t.pollError, setupHelp: false })
    }
  }, [state, message]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (poll.isError) onFailed(failureFrom(poll.error, t.pollError))
  }, [poll.isError]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="mt-4 space-y-3">
      <p className="text-sm">{t.codeInstructions}</p>
      <div className="flex items-center gap-3">
        <span aria-label={t.codeLabel} className="rounded-md bg-muted px-4 py-2 font-mono text-3xl font-semibold tracking-widest">
          {code.userCode}
        </span>
        <CopyCodeButton code={code.userCode} />
      </div>
      <p className="text-sm text-muted-foreground">
        {t.addressLabel}{' '}
        <a
          href={code.verificationUri}
          className="text-foreground underline"
          onClick={(e) => {
            e.preventDefault()
            void window.dockline.openExternal(code.verificationUri).catch(() => {})
          }}
        >
          {code.verificationUri}
        </a>
      </p>
      <p role="status" className="text-sm font-medium">
        {t.waiting}
      </p>
      <Button variant="ghost" onClick={onCancel}>
        {t.cancel}
      </Button>
    </div>
  )
}

function GithubSection({ baseUrl }: { baseUrl: string }) {
  const queryClient = useQueryClient()
  const [code, setCode] = useState<GithubDeviceCode | null>(null)
  const [failure, setFailure] = useState<Failure | null>(null)
  const [confirming, setConfirming] = useState(false)

  const status = useQuery({ queryKey: statusKey(baseUrl), queryFn: () => fetchStatus(baseUrl) })
  const github = status.data?.sources.find((s) => s.source === 'GITHUB')
  const refreshStatus = () => queryClient.invalidateQueries({ queryKey: statusKey(baseUrl) })

  const start = useMutation({
    mutationFn: () => startGithubConnect(baseUrl),
    onMutate: () => setFailure(null),
    onSuccess: (started) => {
      setCode(started)
      void window.dockline.openExternal(started.verificationUri).catch(() => {})
    },
    onError: (error) => setFailure(failureFrom(error, t.startError))
  })
  const disconnect = useMutation({
    mutationFn: () => disconnectGithub(baseUrl),
    onSuccess: () => {
      setConfirming(false)
      return refreshStatus()
    }
  })

  function connectButton(label: string) {
    return (
      <Button onClick={() => start.mutate()} disabled={start.isPending}>
        {start.isPending ? t.starting : label}
      </Button>
    )
  }

  let body
  if (code) {
    body = (
      <Connecting
        baseUrl={baseUrl}
        code={code}
        onCancel={() => setCode(null)}
        onDone={async () => {
          await refreshStatus()
          setCode(null)
        }}
        onFailed={(f) => {
          setCode(null)
          setFailure(f)
        }}
      />
    )
  } else if (failure) {
    body = (
      <div className="mt-4 space-y-3">
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">
          {failure.message}
        </p>
        {failure.setupHelp && <p className="text-sm text-muted-foreground">{t.setupPointer}</p>}
        {connectButton(t.tryAgain)}
      </div>
    )
  } else if (status.isError) {
    body = (
      <p role="alert" className="mt-4 text-sm text-red-600 dark:text-red-400">
        {t.statusLoadError}
      </p>
    )
  } else if (!github) {
    body = null
  } else if (github.state === 'NOT_CONNECTED') {
    body = (
      <div className="mt-4 space-y-3">
        <p className="text-sm text-muted-foreground">{t.notConnectedHint}</p>
        {connectButton(t.connect)}
      </div>
    )
  } else {
    const title = github.state === 'CONNECTED' ? t.connected : github.state === 'LIMITED' ? t.limited : t.error
    body = (
      <div className="mt-4 space-y-3">
        <p className="font-medium">{title}</p>
        {github.message && <p className="text-sm text-muted-foreground">{github.message}</p>}
        {confirming ? (
          <div role="alertdialog" aria-labelledby="disconnect-title" className="space-y-3 rounded-md border border-border p-3">
            <p id="disconnect-title" className="font-medium">
              {t.confirmTitle}
            </p>
            <p className="text-sm text-muted-foreground">{t.confirmText}</p>
            {disconnect.isError && (
              <p role="alert" className="text-sm text-red-600 dark:text-red-400">
                {t.disconnectError}
              </p>
            )}
            <div className="flex gap-2">
              <Button onClick={() => disconnect.mutate()} disabled={disconnect.isPending}>
                {disconnect.isPending ? t.disconnecting : t.confirmYes}
              </Button>
              <Button variant="ghost" onClick={() => setConfirming(false)}>
                {t.confirmNo}
              </Button>
            </div>
          </div>
        ) : (
          <div className="flex gap-2">
            {github.state === 'ERROR' && connectButton(t.reconnect)}
            <Button variant="ghost" onClick={() => setConfirming(true)}>
              {t.disconnect}
            </Button>
          </div>
        )}
      </div>
    )
  }

  return (
    <section aria-labelledby="github-heading" className="mt-6 max-w-xl rounded-md border border-border p-4">
      <h2 id="github-heading" className="text-lg font-semibold">
        {t.heading}
      </h2>
      {body}
    </section>
  )
}

export function SettingsPage({ baseUrl }: { baseUrl: string }) {
  return (
    <section>
      <h1 className="text-2xl font-semibold">{strings.settings.title}</h1>
      <GithubSection baseUrl={baseUrl} />
    </section>
  )
}
