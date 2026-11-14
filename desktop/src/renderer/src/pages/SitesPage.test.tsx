import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SiteDeploy, SiteDeploys } from '@/api/deploys'
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

const deploys = (...list: SiteDeploy[]): SiteDeploys => ({ deploys: list, fetchedAt: '2026-11-14T09:00:00Z' })

/** What GET /api/sites/deploys answers, consumed in order (the last one repeats); "no deploys" by default. */
let deployAnswers: (SiteDeploys | Response)[] = [deploys()]

const deploy = (domain: string, status: SiteDeploy['status'], extra: Partial<SiteDeploy> = {}): SiteDeploy => ({
  domain,
  status,
  at: '2024-05-29T05:51:02Z',
  commit: { sha: 'a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0', message: 'Initial commit' },
  source: 'HOSTINGER',
  problems: [],
  ...extra
})

const noDeploy = (domain: string): SiteDeploy => deploy(domain, 'NONE', { at: null, commit: null, source: 'NONE' })

/**
 * The only fakes: the backend's HTTP API and the preload bridge. The answers are for GET /api/hostinger/sites,
 * consumed in order (the last one repeats); `urls` holds those calls, `linkUrls` the calls for the links and
 * `deployUrls` the calls for the deploys.
 */
function setup(...answers: Answer[]) {
  const urls: string[] = []
  const linkUrls: string[] = []
  const deployUrls: string[] = []
  const openExternal = vi.fn(async () => {})
  window.dockline = { getBackendState: vi.fn(), retryBackend: vi.fn(), openExternal }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.endsWith('/api/sites/links')) {
        linkUrls.push(url)
        return linksAnswer instanceof Response ? linksAnswer.clone() : new Response(JSON.stringify(linksAnswer))
      }
      if (url.includes('/api/sites/deploys')) {
        deployUrls.push(url)
        const d = deployAnswers[Math.min(deployUrls.length - 1, deployAnswers.length - 1)]
        return d instanceof Response ? d.clone() : new Response(JSON.stringify(d))
      }
      urls.push(url)
      const a = answers[Math.min(urls.length - 1, answers.length - 1)]
      return a instanceof Response ? a.clone() : new Response(JSON.stringify(a))
    })
  )
  return { urls, linkUrls, deployUrls, openExternal }
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
  return render(
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
    deployAnswers = [deploys()]
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

  describe('deploy status', () => {
    const DEPLOYS_URL = `${BASE_URL}/api/sites/deploys?refresh=false`
    let visibility: DocumentVisibilityState = 'visible'

    const setVisibility = (state: DocumentVisibilityState) => {
      visibility = state
      act(() => {
        document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
      })
    }
    const advance = (ms: number) =>
      act(async () => {
        await vi.advanceTimersByTimeAsync(ms)
      })
    // Only what the automatic refresh uses; fetch and the test's own waits stay real.
    const fakeClock = () => vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })

    beforeEach(() => {
      visibility = 'visible'
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility })
      vi.spyOn(document, 'hasFocus').mockReturnValue(true)
    })
    afterEach(() => {
      Reflect.deleteProperty(document, 'visibilityState')
      vi.restoreAllMocks()
    })

    it('shows badge, time, short commit and source for a succeeded deploy', async () => {
      deployAnswers = [deploys(deploy('app.example', 'SUCCEEDED'))]
      setup(list([site('APP.example', { websiteType: 'nodejs' })]))
      renderPage()
      const badge = await screen.findByTestId('deploy-badge')
      const row = screen.getByTestId('site-row')
      expect(row).toContainElement(badge)
      expect(badge).toHaveTextContent('Succeeded')
      expect(row.querySelector('time')).toHaveAttribute('datetime', '2024-05-29T05:51:02Z')
      expect(row.querySelector('time')).toHaveTextContent(/ago$/)
      const commit = screen.getByTestId('deploy-commit')
      expect(commit).toHaveTextContent(/^a1b2c3d$/)
      expect(commit).toHaveAttribute('title', 'Initial commit')
      expect(row).toHaveTextContent('Hostinger')
      expect(row).not.toHaveTextContent('No deploy information')
    })

    it('shows failed, in-progress and unknown badges; a commit without a message has no tooltip', async () => {
      deployAnswers = [
        deploys(
          deploy('a.com', 'FAILED', { commit: { sha: 'ffffffffffffffff', message: null } }),
          deploy('b.com', 'IN_PROGRESS', { commit: null }),
          deploy('c.com', 'UNKNOWN', { at: null, commit: null })
        )
      ]
      setup(list([site('a.com'), site('b.com'), site('c.com')]))
      renderPage()
      await screen.findAllByTestId('deploy-badge')
      const rows = screen.getAllByTestId('site-row')
      expect(rows[0]).toHaveTextContent('Failed')
      expect(rows[0].querySelector('[data-testid="deploy-commit"]')).not.toHaveAttribute('title')
      expect(rows[1]).toHaveTextContent('In progress')
      expect(rows[1].querySelector('[data-testid="deploy-commit"]')).toBeNull()
      expect(rows[2]).toHaveTextContent('Unknown')
      expect(rows[2].querySelector('time')).toBeNull()
    })

    it('a site that is not a Node.js website, or has no entry, shows "No deploy information"', async () => {
      deployAnswers = [deploys(noDeploy('blog.example'))]
      setup(list([site('blog.example'), site('other.example'), site(null)]))
      renderPage()
      await screen.findAllByText('No deploy information')
      for (const row of screen.getAllByTestId('site-row')) {
        expect(row).toHaveTextContent('No deploy information')
        expect(row.querySelector('[data-testid="deploy-badge"]')).toBeNull()
      }
    })

    it('a failing deploys call leaves the list and the links intact, with no alert', async () => {
      linksAnswer = { links: [link('a.com', 'octo/site-a')], complete: true, problems: [] }
      deployAnswers = [problem(502, 'HOSTINGER_UNAVAILABLE', 'down')]
      const fake = setup(list([site('a.com'), site('b.com')]))
      renderPage()
      expect(await screen.findByRole('button', { name: 'octo/site-a' })).toBeInTheDocument()
      await waitFor(() => expect(fake.deployUrls).toHaveLength(1))
      expect(screen.getAllByTestId('site-row')).toHaveLength(2)
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.queryByTestId('deploy-status')).not.toBeInTheDocument()
      expect(await screen.findByTestId('deploy-trouble')).toHaveTextContent('Deploy status could not be updated. down')
    })

    it('a failed update says so once, keeps the last statuses and keeps refreshing', async () => {
      deployAnswers = [
        deploys(deploy('a.com', 'SUCCEEDED'), deploy('b.com', 'IN_PROGRESS')),
        problem(502, 'HOSTINGER_UNAVAILABLE', 'Hostinger did not answer.'),
        deploys(deploy('a.com', 'SUCCEEDED'), deploy('b.com', 'FAILED'))
      ]
      const fake = setup(list([site('a.com'), site('b.com')]))
      fakeClock()
      renderPage()
      await screen.findByText('Succeeded')
      await advance(30_000)
      const notes = await screen.findAllByTestId('deploy-trouble')
      expect(notes).toHaveLength(1)
      expect(notes[0]).toHaveTextContent('Deploy status could not be updated. Hostinger did not answer.')
      expect(notes[0]).toHaveAttribute('role', 'status')
      expect(screen.getAllByTestId('site-row')).toHaveLength(2)
      expect(screen.getByText('Succeeded')).toBeInTheDocument()
      expect(screen.getByText('In progress')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      await advance(30_000)
      expect(await screen.findByText('Failed')).toBeInTheDocument()
      expect(fake.deployUrls).toHaveLength(3)
      await waitFor(() => expect(screen.queryByTestId('deploy-trouble')).not.toBeInTheDocument())
    })

    it('while rate limited it counts down, keeps the list, waits, then asks again', async () => {
      deployAnswers = [
        deploys(deploy('a.com', 'IN_PROGRESS')),
        problem(429, 'HOSTINGER_RATE_LIMITED', 'Slow down', { retryAfterSeconds: 60 }),
        deploys(deploy('a.com', 'SUCCEEDED'))
      ]
      const fake = setup(list([site('a.com'), site('b.com')]))
      fakeClock()
      renderPage()
      await screen.findByText('In progress')
      await advance(30_000)
      expect(await screen.findByTestId('deploy-trouble')).toHaveTextContent('Deploy status: rate limited, retrying in 60 s')
      expect(screen.getAllByTestId('site-row')).toHaveLength(2)
      expect(screen.getByText('In progress')).toBeInTheDocument()
      await advance(45_000) // longer than the normal interval: still waiting
      expect(screen.getByTestId('deploy-trouble')).toHaveTextContent('retrying in 15 s')
      expect(fake.deployUrls).toHaveLength(2)
      await advance(15_000)
      expect(await screen.findByText('Succeeded')).toBeInTheDocument()
      expect(fake.deployUrls).toHaveLength(3)
      await waitFor(() => expect(screen.queryByTestId('deploy-trouble')).not.toBeInTheDocument())
      expect(fake.urls).toHaveLength(1) // the site list itself was never asked for again
    })

    it('a status that changes between two automatic refreshes updates the row without a click', async () => {
      deployAnswers = [deploys(deploy('a.com', 'IN_PROGRESS')), deploys(deploy('a.com', 'SUCCEEDED'))]
      const fake = setup(list([site('a.com')]))
      fakeClock()
      renderPage()
      expect(await screen.findByText('In progress')).toBeInTheDocument()
      await advance(29_000)
      expect(fake.deployUrls).toEqual([DEPLOYS_URL])
      await advance(1_000)
      expect(await screen.findByText('Succeeded')).toBeInTheDocument()
      expect(screen.queryByText('In progress')).not.toBeInTheDocument()
      expect(fake.deployUrls).toEqual([DEPLOYS_URL, DEPLOYS_URL])
      expect(fake.urls).toHaveLength(1) // the site list itself is not asked for again
    })

    it('keeps the last known statuses when a later deploys call fails', async () => {
      deployAnswers = [deploys(deploy('a.com', 'SUCCEEDED')), problem(429, 'HOSTINGER_RATE_LIMITED', 'Slow down')]
      const fake = setup(list([site('a.com')]))
      fakeClock()
      renderPage()
      await screen.findByText('Succeeded')
      await advance(30_000)
      await waitFor(() => expect(fake.deployUrls).toHaveLength(2))
      await advance(0)
      expect(screen.getByText('Succeeded')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('a site that could not be read this time keeps the status it had', async () => {
      const limited = { source: 'HOSTINGER', code: 'HOSTINGER_RATE_LIMITED', message: 'Slow down', retryAfterSeconds: 42 }
      deployAnswers = [
        deploys(deploy('a.com', 'FAILED')),
        deploys(deploy('a.com', 'UNKNOWN', { at: null, commit: null, problems: [limited] as SiteDeploy['problems'] }))
      ]
      const fake = setup(list([site('a.com')]))
      fakeClock()
      renderPage()
      await screen.findByText('Failed')
      await advance(30_000)
      await waitFor(() => expect(fake.deployUrls).toHaveLength(2))
      await advance(0)
      expect(screen.getByText('Failed')).toBeInTheDocument()
      expect(screen.queryByText('Unknown')).not.toBeInTheDocument()
      // The site's own rate limit is told once, with its wait; the next question comes after it, not before.
      expect(screen.getByTestId('deploy-trouble')).toHaveTextContent('Deploy status: rate limited, retrying in 42 s')
      await advance(41_000)
      expect(fake.deployUrls).toHaveLength(2)
      await advance(1_000)
      await waitFor(() => expect(fake.deployUrls).toHaveLength(3))
    })

    it('does not refresh while the window is hidden, and refreshes at once when it is shown again', async () => {
      deployAnswers = [deploys(deploy('a.com', 'IN_PROGRESS')), deploys(deploy('a.com', 'SUCCEEDED'))]
      const fake = setup(list([site('a.com')]))
      fakeClock()
      renderPage()
      await screen.findByText('In progress')
      setVisibility('hidden')
      await advance(120_000)
      expect(fake.deployUrls).toHaveLength(1)
      setVisibility('visible')
      expect(await screen.findByText('Succeeded')).toBeInTheDocument()
      expect(fake.deployUrls).toHaveLength(2)
      await advance(30_000)
      expect(fake.deployUrls).toHaveLength(3)
    })

    it('does not refresh after the window lost focus, and starts again when it gets focus back', async () => {
      const fake = setup(list([site('a.com')]))
      deployAnswers = [deploys(deploy('a.com', 'SUCCEEDED'))]
      fakeClock()
      renderPage()
      await screen.findByText('Succeeded')
      act(() => {
        window.dispatchEvent(new Event('blur'))
      })
      await advance(10_000)
      expect(fake.deployUrls).toHaveLength(1)
      // Back before the data is 30 seconds old: no extra request, the normal rhythm continues.
      act(() => {
        window.dispatchEvent(new Event('focus'))
      })
      await advance(0)
      expect(fake.deployUrls).toHaveLength(1)
      await advance(30_000)
      expect(fake.deployUrls).toHaveLength(2)
      act(() => {
        window.dispatchEvent(new Event('blur'))
      })
      await advance(120_000)
      expect(fake.deployUrls).toHaveLength(2)
      act(() => {
        window.dispatchEvent(new Event('focus'))
      })
      await advance(0)
      expect(fake.deployUrls).toHaveLength(3)
    })

    it('stops refreshing when the view is left', async () => {
      deployAnswers = [deploys(deploy('a.com', 'SUCCEEDED'))]
      const fake = setup(list([site('a.com')]))
      fakeClock()
      const { unmount } = renderPage()
      await screen.findByText('Succeeded')
      unmount()
      await advance(120_000)
      expect(fake.deployUrls).toHaveLength(1)
    })

    it('Refresh asks for the deploys again with refresh=true', async () => {
      const user = userEvent.setup()
      deployAnswers = [deploys(deploy('a.com', 'IN_PROGRESS')), deploys(deploy('a.com', 'FAILED'))]
      const fake = setup(list([site('a.com')]))
      renderPage()
      await screen.findByText('In progress')
      await user.click(screen.getByRole('button', { name: 'Refresh' }))
      expect(await screen.findByText('Failed')).toBeInTheDocument()
      expect(fake.deployUrls).toEqual([DEPLOYS_URL, `${BASE_URL}/api/sites/deploys?refresh=true`])
    })
  })
})
