import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LogEntry } from '@/api/log'
import { LogPage } from './LogPage'

const BASE_URL = 'http://127.0.0.1:5555'

function entry(id: number, overrides: Partial<LogEntry> = {}): LogEntry {
  return {
    id,
    time: `2026-01-01T10:00:0${id}Z`,
    level: 'ERROR',
    source: 'GITHUB',
    action: `GET /thing/${id}`,
    statusCode: 500,
    message: `Message ${id}`,
    context: { appVersion: '1.2.3' },
    ...overrides
  }
}

/** The only fake: the backend's HTTP API, kept in memory. Returns entries newest first like the real one. */
function fakeBackend(initial: LogEntry[]) {
  let entries = [...initial]
  const calls: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const u = new URL(url)
      calls.push(`${init?.method ?? 'GET'} ${u.pathname}${u.search}`)
      if (init?.method === 'DELETE') {
        entries = []
        return new Response(null, { status: 204 })
      }
      const source = u.searchParams.get('source')
      const level = u.searchParams.get('level')
      const result = entries
        .filter((e) => (!source || e.source === source) && (!level || e.level === level))
        .sort((a, b) => b.id - a.id)
      return new Response(JSON.stringify(result))
    })
  )
  return {
    calls,
    add: (e: LogEntry) => {
      entries.push(e)
    }
  }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <LogPage baseUrl={BASE_URL} />
    </QueryClientProvider>
  )
}

describe('Log page', () => {
  beforeEach(() => vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: vi.fn(async () => {}) } }))
  afterEach(() => vi.unstubAllGlobals())

  it('lists entries newest first with level, source, action, status code and message', async () => {
    fakeBackend([entry(1), entry(2, { source: 'HOSTINGER', level: 'WARN', statusCode: 429, message: 'Slow down' })])
    renderPage()

    const items = await screen.findAllByTestId('log-entry')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('HOSTINGER')
    expect(items[0]).toHaveTextContent('WARN')
    expect(items[0]).toHaveTextContent('GET /thing/2')
    expect(items[0]).toHaveTextContent('429')
    expect(items[0]).toHaveTextContent('Slow down')
    expect(items[1]).toHaveTextContent('Message 1')
  })

  it('shows a friendly empty state', async () => {
    fakeBackend([])
    renderPage()
    expect(await screen.findByText('No errors')).toBeInTheDocument()
  })

  it('filters by source and level on the server', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend([
      entry(1, { source: 'GITHUB', level: 'ERROR' }),
      entry(2, { source: 'HOSTINGER', level: 'WARN' }),
      entry(3, { source: 'HOSTINGER', level: 'ERROR' })
    ])
    renderPage()
    expect(await screen.findAllByTestId('log-entry')).toHaveLength(3)

    await user.selectOptions(screen.getByLabelText('Source'), 'HOSTINGER')
    await vi.waitFor(() => expect(screen.getAllByTestId('log-entry')).toHaveLength(2))
    expect(backend.calls).toContain('GET /api/log?source=HOSTINGER')

    await user.selectOptions(screen.getByLabelText('Level'), 'WARN')
    await vi.waitFor(() => expect(screen.getAllByTestId('log-entry')).toHaveLength(1))
    expect(backend.calls).toContain('GET /api/log?source=HOSTINGER&level=WARN')
    expect(screen.getByTestId('log-entry')).toHaveTextContent('HOSTINGER')
  })

  it('clears the log and then shows the empty state', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend([entry(1)])
    renderPage()
    await screen.findByTestId('log-entry')

    await user.click(screen.getByRole('button', { name: 'Clear' }))

    expect(await screen.findByText('No errors')).toBeInTheDocument()
    expect(backend.calls).toContain('DELETE /api/log')
  })

  it('refresh picks up new entries', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend([entry(1)])
    renderPage()
    await screen.findByTestId('log-entry')

    backend.add(entry(2))
    await user.click(screen.getByRole('button', { name: 'Refresh' }))

    await vi.waitFor(() => expect(screen.getAllByTestId('log-entry')).toHaveLength(2))
  })

  it('expands an entry to show request, response and stack trace', async () => {
    const user = userEvent.setup()
    fakeBackend([
      entry(1, { context: { appVersion: '1.2.3', request: 'GET https://api/x', response: '{"boom":1}', stackTrace: 'at Foo.bar(Foo.java:1)' } })
    ])
    renderPage()
    const item = await screen.findByTestId('log-entry')

    await user.click(within(item).getByText('Message 1'))

    expect(item).toHaveTextContent('GET https://api/x')
    expect(item).toHaveTextContent('{"boom":1}')
    expect(item).toHaveTextContent('at Foo.bar(Foo.java:1)')
  })

  it('"Copy for AI" copies a readable block with all context and masks tokens again', async () => {
    const user = userEvent.setup()
    fakeBackend([
      entry(1, {
        message: 'Bad credentials for ghp_0123456789abcdefTESTtokenNOTreal1234',
        context: {
          appVersion: '1.2.3',
          request: 'GET https://api/user\nAuthorization: Bearer secretvalue',
          response: '{"message":"Bad credentials"}',
          stackTrace: 'java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.java:1)'
        }
      })
    ])
    const writeText = vi.fn(async () => {})
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    renderPage()
    await screen.findByTestId('log-entry')

    await user.click(screen.getByRole('button', { name: 'Copy for AI' }))

    expect(writeText).toHaveBeenCalledOnce()
    const text = (writeText.mock.calls[0] as unknown as [string])[0]
    for (const part of [
      'App version: 1.2.3',
      'Time: 2026-01-01T10:00:01Z',
      'Source: GITHUB',
      'Action: GET /thing/1',
      'Status code: 500',
      'GET https://api/user',
      '{"message":"Bad credentials"}',
      'java.lang.IllegalStateException: boom',
      'ghp_****',
      'Authorization: Bearer ****'
    ]) {
      expect(text).toContain(part)
    }
    expect(text).not.toContain('0123456789abcdef')
    expect(text).not.toContain('secretvalue')
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
  })
})
