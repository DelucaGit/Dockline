import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HostingerSite, HostingerSites } from '@/api/hostinger'
import type { SiteLink, SiteLinks } from '@/api/sites'
import { SitesPage } from './SitesPage'

const BASE_URL = 'http://127.0.0.1:5555'

function site(domain: string | null, extra: Partial<HostingerSite> = {}): HostingerSite {
  return {
    domain,
    username: 'u1',
    orderId: 1,
    websiteType: 'wordpress',
    rootDirectory: null,
    enabled: true,
    createdAt: null,
    ...extra
  }
}

type Answer = HostingerSites | Response

const NO_LINKS: SiteLinks = { links: [], complete: true, problems: [] }

/** What GET /api/sites/links answers; set per test, "no links" by default. */
let linksAnswer: SiteLinks | Response = NO_LINKS

const link = (domain: string, fullName: string | null, extra: Partial<SiteLink> = {}): SiteLink => ({
  domain,
  repo: fullName ? { fullName, htmlUrl: `https://github.com/${fullName}` } : null,
  source: fullName ? 'MANUAL' : 'NONE',
  hostingerChecked: true,
  ...extra
})

/**
 * The only fakes: the backend's HTTP API and the preload bridge. The answers are for GET /api/hostinger/sites,
 * consumed in order (the last one repeats); `urls` holds those calls and `linkUrls` the calls for the links.
 */
function setup(...answers: Answer[]) {
  const urls: string[] = []
  const linkUrls: string[] = []
  const openExternal = vi.fn(async () => {})
  window.dockline = { getBackendState: vi.fn(), retryBackend: vi.fn(), openExternal }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.endsWith('/api/sites/links')) {
        linkUrls.push(url)
        return linksAnswer instanceof Response ? linksAnswer.clone() : new Response(JSON.stringify(linksAnswer))
      }
      urls.push(url)
      const a = answers[Math.min(urls.length - 1, answers.length - 1)]
      return a instanceof Response ? a.clone() : new Response(JSON.stringify(a))
    })
  )
  return { urls, linkUrls, openExternal }
}

const list = (sites: HostingerSite[], extra: Partial<HostingerSites> = {}): HostingerSites => ({
  sites,
  fetchedAt: '2026-11-13T09:00:00Z',
  truncated: false,
  ...extra
})

const problem = (status: number, code: string, detail: string, extra: Record<string, unknown> = {}, headers = {}) =>
  new Response(JSON.stringify({ status, code, detail, ...extra }), { status, headers })

function Where() {
  const loc = useLocation()
  return <p data-testid="where">{loc.pathname + (loc.state ? JSON.stringify(loc.state) : '')}</p>
}

function renderPage(retry: number | false = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry, retryDelay: 0 } } })
  render(
    <MemoryRouter initialEntries={['/sites']}>
      <QueryClientProvider client={client}>
        <SitesPage baseUrl={BASE_URL} />
        <Where />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

describe('SitesPage', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    linksAnswer = NO_LINKS
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('lists every website, including one without a domain and a suspended one', async () => {
    setup(list([site('a.com'), site('b.com', { enabled: false }), site(null, { websiteType: null })]))
    renderPage()
    const rows = await screen.findAllByTestId('site-row')
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('a.com')
    expect(rows[1]).toHaveTextContent('Suspended')
    expect(rows[2]).toHaveTextContent('(no domain)')
    expect(screen.getByText(/Last updated/)).toBeInTheDocument()
  })

  it('shows "No websites" for an empty plan', async () => {
    setup(list([]))
    renderPage()
    expect(await screen.findByText('No websites')).toBeInTheDocument()
  })

  it('opening reuses the backend copy; Refresh asks Hostinger again', async () => {
    const user = userEvent.setup()
    const fake = setup(list([site('a.com')]), list([site('a.com'), site('new.com')], { fetchedAt: '2026-11-13T09:30:45Z' }))
    renderPage()
    await screen.findByText('a.com')
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('new.com')).toBeInTheDocument()
    expect(fake.urls).toEqual([`${BASE_URL}/api/hostinger/sites`, `${BASE_URL}/api/hostinger/sites?refresh=true`])
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

  it('an error shows a readable message and Try again fetches fresh data', async () => {
    const user = userEvent.setup()
    const fake = setup(problem(502, 'HOSTINGER_UNAVAILABLE', 'Hostinger could not be reached.'), list([site('a.com')]))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Hostinger could not be reached.')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('a.com')).toBeInTheDocument()
    expect(fake.urls[1]).toContain('refresh=true')
  })

  it('an automatic retry of a failed Refresh still asks Hostinger again (refresh=true)', async () => {
    const user = userEvent.setup()
    const fake = setup(
      list([site('a.com')]),
      problem(502, 'HOSTINGER_UNAVAILABLE', 'down'),
      list([site('a.com'), site('b.com')])
    )
    renderPage(1)
    await screen.findByText('a.com')
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('b.com')).toBeInTheDocument()
    expect(fake.urls).toEqual([
      `${BASE_URL}/api/hostinger/sites`,
      `${BASE_URL}/api/hostinger/sites?refresh=true`,
      `${BASE_URL}/api/hostinger/sites?refresh=true`
    ])
  })

  it('while rate limited it counts down, then asks again and shows the list', async () => {
    const fake = setup(
      problem(429, 'HOSTINGER_RATE_LIMITED', 'Slow down', { retryAfterSeconds: 3 }, { 'Retry-After': '3' }),
      list([site('a.com')])
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

  describe('linked repos and opening a site', () => {
    it('shows the linked repo of each site; clicking it opens GitHub and stays on the list', async () => {
      const user = userEvent.setup()
      linksAnswer = { links: [link('a.com', 'octo/site-a'), link('b.com', null)], complete: true, problems: [] }
      const fake = setup(list([site('A.com'), site('b.com')]))
      renderPage()
      const repo = await screen.findByRole('button', { name: 'octo/site-a' })
      const rows = screen.getAllByTestId('site-row')
      expect(rows[0]).toContainElement(repo)
      expect(rows[1].querySelector('[data-testid="site-row-repo"]')).toBeNull()

      await user.click(repo)
      expect(fake.openExternal).toHaveBeenCalledWith('https://github.com/octo/site-a')
      expect(screen.getByTestId('where')).toHaveTextContent(/^\/sites$/)
    })

    it('clicking a site opens its detail view; a site without a domain is not a link', async () => {
      const user = userEvent.setup()
      setup(list([site('a.com'), site(null)]))
      renderPage()
      const rows = await screen.findAllByTestId('site-row')
      expect(rows[1].querySelector('a')).toBeNull()
      await user.click(screen.getByRole('link', { name: 'a.com' }))
      expect(screen.getByTestId('where')).toHaveTextContent('/sites/a.com')
    })

    it('the keyboard opens a site too', async () => {
      const user = userEvent.setup()
      setup(list([site('a.com')]))
      renderPage()
      ;(await screen.findByRole('link', { name: 'a.com' })).focus()
      await user.keyboard('{Enter}')
      expect(screen.getByTestId('where')).toHaveTextContent('/sites/a.com')
    })

    it('a failing links call does not break the list: the sites show, without repos', async () => {
      linksAnswer = problem(500, 'INTERNAL', 'boom')
      const fake = setup(list([site('a.com'), site('b.com')]))
      renderPage()
      expect(await screen.findAllByTestId('site-row')).toHaveLength(2)
      await waitFor(() => expect(fake.linkUrls).toHaveLength(1))
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.queryByTestId('site-row-repo')).not.toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'a.com' })).toBeInTheDocument()
    })

    it('a site Hostinger was not asked about yet shows no repo', async () => {
      linksAnswer = {
        links: [link('a.com', 'octo/site-a', { hostingerChecked: false }), link('b.com', 'octo/site-b')],
        complete: false,
        problems: []
      }
      setup(list([site('a.com'), site('b.com')]))
      renderPage()
      expect(await screen.findByRole('button', { name: 'octo/site-b' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'octo/site-a' })).not.toBeInTheDocument()
    })

    it('the links are not asked for while the list itself fails, and Refresh asks for them again', async () => {
      const user = userEvent.setup()
      linksAnswer = { links: [link('a.com', 'octo/site-a')], complete: true, problems: [] }
      const fake = setup(problem(502, 'HOSTINGER_UNAVAILABLE', 'down'), list([site('a.com')]))
      renderPage()
      await screen.findByRole('alert')
      expect(fake.linkUrls).toHaveLength(0)
      await user.click(screen.getByRole('button', { name: 'Try again' }))
      expect(await screen.findByRole('button', { name: 'octo/site-a' })).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Refresh' }))
      await waitFor(() => expect(fake.linkUrls.length).toBeGreaterThanOrEqual(2))
    })
  })
})
