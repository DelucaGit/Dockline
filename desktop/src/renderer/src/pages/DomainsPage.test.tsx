import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HostingerDomain, HostingerDomains } from '@/api/hostinger'
import { DomainsPage } from './DomainsPage'

const BASE_URL = 'http://127.0.0.1:5555'
const DAY = 24 * 60 * 60 * 1000
const inDays = (days: number) => new Date(Date.now() + days * DAY).toISOString()

function domain(name: string | null, expiresAt: string | null, extra: Partial<HostingerDomain> = {}): HostingerDomain {
  return { domain: name, expiresAt, status: 'active', type: 'domain', createdAt: null, ...extra }
}

type Answer = HostingerDomains | Response

/** The only fake: the backend's HTTP API. Answers are consumed in order; the last one repeats. */
function setup(...answers: Answer[]) {
  const urls: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      urls.push(url)
      const a = answers[Math.min(urls.length - 1, answers.length - 1)]
      return a instanceof Response ? a.clone() : new Response(JSON.stringify(a))
    })
  )
  return { urls }
}

const list = (domains: HostingerDomain[], extra: Partial<HostingerDomains> = {}): HostingerDomains => ({
  domains,
  fetchedAt: '2026-11-13T09:00:00Z',
  ...extra
})

const problem = (status: number, code: string, detail: string, extra: Record<string, unknown> = {}, headers = {}) =>
  new Response(JSON.stringify({ status, code, detail, ...extra }), { status, headers })

function Where() {
  const loc = useLocation()
  return <p data-testid="where">{loc.pathname + (loc.state ? JSON.stringify(loc.state) : '')}</p>
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <MemoryRouter initialEntries={['/domains']}>
      <QueryClientProvider client={client}>
        <DomainsPage baseUrl={BASE_URL} />
        <Where />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

describe('DomainsPage', () => {
  beforeEach(() => vi.unstubAllGlobals())
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('lists domains in the given order and marks expired and expiring-soon ones with a text label', async () => {
    setup(
      list([
        domain('old.com', inDays(-3)),
        domain('soon.com', inDays(10)),
        domain('flagged.com', inDays(400), { status: 'expired' }),
        domain('later.com', inDays(200)),
        domain(null, null)
      ])
    )
    renderPage()
    const rows = await screen.findAllByTestId('domain-row')
    expect(rows.map((r) => r.getAttribute('data-expiry'))).toEqual(['expired', 'soon', 'expired', 'ok', 'ok'])
    expect(rows[0]).toHaveTextContent('old.com')
    expect(rows[0]).toHaveTextContent('Expired')
    expect(rows[1]).toHaveTextContent('Expiring soon')
    expect(rows[2]).toHaveTextContent('Expired')
    expect(rows[3]).not.toHaveTextContent(/Expiring soon|Expired/)
    expect(rows[3]).toHaveTextContent('Expires')
    expect(rows[4]).toHaveTextContent('(no name)')
    expect(rows[4]).toHaveTextContent('No expiry date')
    expect(screen.getByText(/Last updated/)).toBeInTheDocument()
  })

  it('29 days away is expiring soon; 31 days is not', async () => {
    setup(list([domain('edge.com', inDays(29)), domain('beyond.com', inDays(31))]))
    renderPage()
    const rows = await screen.findAllByTestId('domain-row')
    expect(rows.map((r) => r.getAttribute('data-expiry'))).toEqual(['soon', 'ok'])
  })

  it('shows "No domains" for an empty list', async () => {
    setup(list([]))
    renderPage()
    expect(await screen.findByText('No domains')).toBeInTheDocument()
  })

  it('opening reuses the backend copy; Refresh asks Hostinger again', async () => {
    const user = userEvent.setup()
    const fake = setup(list([domain('a.com', inDays(90))]), list([domain('a.com', inDays(90)), domain('new.com', inDays(95))]))
    renderPage()
    await screen.findByText('a.com')
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('new.com')).toBeInTheDocument()
    expect(fake.urls).toEqual([`${BASE_URL}/api/hostinger/domains`, `${BASE_URL}/api/hostinger/domains?refresh=true`])
  })

  it('"not connected" links to Settings', async () => {
    const user = userEvent.setup()
    setup(problem(409, 'HOSTINGER_NOT_CONNECTED', 'No token'))
    renderPage()
    expect(await screen.findByText('Hostinger is not connected')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Open Settings' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/settings')
  })

  it('a rejected token offers to replace it', async () => {
    const user = userEvent.setup()
    setup(problem(409, 'HOSTINGER_TOKEN_REJECTED', 'Revoked'))
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Replace token' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/settings{"replaceToken":true}')
  })

  it('an error shows the message above the last known domains, and Try again fetches fresh data', async () => {
    const user = userEvent.setup()
    const fake = setup(
      list([domain('a.com', inDays(90))]),
      problem(502, 'HOSTINGER_UNAVAILABLE', 'Hostinger could not be reached.'),
      list([domain('a.com', inDays(90)), domain('b.com', inDays(95))])
    )
    renderPage()
    await screen.findByText('a.com')
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Hostinger could not be reached.')
    expect(screen.getByText('a.com')).toBeInTheDocument()
    expect(alert.compareDocumentPosition(screen.getByTestId('domain-row')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText(/Last updated/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('b.com')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(fake.urls[2]).toContain('refresh=true')
  })

  it('a first-time error without data shows just the message', async () => {
    setup(problem(502, 'HOSTINGER_UNAVAILABLE', 'Hostinger could not be reached.'))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Hostinger could not be reached.')
    expect(screen.queryByTestId('domain-row')).not.toBeInTheDocument()
  })

  it('while rate limited it counts down, then asks again and shows the list', async () => {
    const fake = setup(
      problem(429, 'HOSTINGER_RATE_LIMITED', 'Slow down', { retryAfterSeconds: 3 }, { 'Retry-After': '3' }),
      list([domain('a.com', inDays(90))])
    )
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] }) // the countdown only; fetch and waits stay real
    renderPage()
    expect(await screen.findByText('Rate limited, retrying in 3 s')).toBeInTheDocument()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(screen.getByText('Rate limited, retrying in 2 s')).toBeInTheDocument()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2100)
    })
    expect(await screen.findByText('a.com')).toBeInTheDocument()
    expect(fake.urls).toHaveLength(2)
  })
})
