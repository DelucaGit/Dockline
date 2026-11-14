import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SourceStatus } from '@/api/github'
import type { Overview, OverviewDeploy, OverviewProblem } from '@/api/overview'
import type { SiteSourceProblem } from '@/api/sites'
import { OverviewPage } from './OverviewPage'

const BASE_URL = 'http://127.0.0.1:5555'
const CACHED_URL = `${BASE_URL}/api/overview?refresh=false`
const FRESH_URL = `${BASE_URL}/api/overview?refresh=true`

const CONNECTED: SourceStatus[] = [
  { source: 'GITHUB', state: 'CONNECTED', message: 'Connected as octocat', retryAfterSeconds: null },
  { source: 'HOSTINGER', state: 'CONNECTED', message: null, retryAfterSeconds: null }
]

const deploy = (domain: string, status: OverviewDeploy['status'], extra: Partial<OverviewDeploy> = {}): OverviewDeploy => ({
  domain,
  status,
  at: '2026-11-14T08:00:00Z',
  commit: { sha: 'a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0', message: 'Initial commit' },
  ...extra
})

const failedDeploy: OverviewProblem = {
  kind: 'DEPLOY_FAILED',
  source: 'HOSTINGER',
  domain: 'b.com',
  repoFullName: null,
  at: '2026-11-14T07:00:00Z',
  message: 'The latest deploy of b.com failed.'
}

const sourceError = (source: 'GITHUB' | 'HOSTINGER', code: string, message: string, retryAfterSeconds: number | null = null): SiteSourceProblem => ({
  source,
  code: `${source}_${code}`,
  message,
  retryAfterSeconds
})

function overview(extra: Partial<Overview> = {}): Overview {
  return {
    sites: { count: 3, errors: [] },
    repos: { count: 12, errors: [] },
    problems: { items: [failedDeploy], errors: [] },
    latestDeploys: { deploys: [deploy('a.com', 'SUCCEEDED'), deploy('b.com', 'FAILED', { commit: null })], errors: [] },
    sources: CONNECTED,
    fetchedAt: '2026-11-14T09:00:00Z',
    ...extra
  }
}

/** Only the backend's HTTP API is faked. Answers are consumed in order (the last one repeats); `urls` holds the calls. */
function setup(...answers: (Overview | Response)[]) {
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

function Where() {
  const loc = useLocation()
  return <p data-testid="where">{loc.pathname + (loc.state ? JSON.stringify(loc.state) : '')}</p>
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/overview']}>
      <QueryClientProvider client={client}>
        <OverviewPage baseUrl={BASE_URL} />
        <Where />
      </QueryClientProvider>
    </MemoryRouter>
  )
}

const advance = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
// Only what the automatic refresh uses; fetch and the test's own waits stay real.
const fakeClock = () => vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })

describe('OverviewPage', () => {
  let visibility: DocumentVisibilityState = 'visible'

  beforeEach(() => {
    vi.unstubAllGlobals()
    visibility = 'visible'
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility })
    vi.spyOn(document, 'hasFocus').mockReturnValue(true)
  })
  afterEach(() => {
    vi.useRealTimers()
    Reflect.deleteProperty(document, 'visibilityState')
    vi.restoreAllMocks()
  })

  it('shows the counts, the problems, the latest deploys and the sources', async () => {
    setup(overview())
    renderPage()
    expect(await screen.findByTestId('overview-count-sites-value')).toHaveTextContent('3')
    expect(screen.getByTestId('overview-count-repos-value')).toHaveTextContent('12')
    expect(screen.getByTestId('overview-count-problems-value')).toHaveTextContent('1')

    const problem = screen.getByTestId('overview-problem')
    expect(problem).toHaveAttribute('data-kind', 'DEPLOY_FAILED')
    expect(problem).toHaveTextContent('Deploy failed')
    expect(problem).toHaveTextContent('The latest deploy of b.com failed.')

    const deploys = screen.getAllByTestId('overview-deploy')
    expect(deploys).toHaveLength(2)
    expect(deploys[0]).toHaveTextContent('a.com')
    expect(within(deploys[0]).getByTestId('deploy-badge')).toHaveTextContent('Succeeded')
    expect(within(deploys[0]).getByTestId('deploy-commit')).toHaveTextContent('a1b2c3d')
    // A deploy without a known commit shows no commit.
    expect(within(deploys[1]).getByTestId('deploy-badge')).toHaveTextContent('Failed')
    expect(within(deploys[1]).queryByTestId('deploy-commit')).toBeNull()

    expect(screen.getByTestId('overview-source-github')).toHaveTextContent('Connected as octocat')
    expect(screen.getByTestId('overview-source-hostinger')).toHaveAttribute('data-state', 'CONNECTED')
    expect(screen.getByText('Last updated').querySelector('time')).toHaveAttribute('dateTime', '2026-11-14T09:00:00Z')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('shows each part loading until the answer is there', async () => {
    let answer: (r: Response) => void = () => {}
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => (answer = resolve))))
    renderPage()
    expect(screen.getAllByRole('status').length).toBeGreaterThanOrEqual(5)
    await act(async () => answer(new Response(JSON.stringify(overview()))))
    expect(await screen.findByTestId('overview-count-sites-value')).toHaveTextContent('3')
    expect(screen.queryByText('Loading…')).toBeNull()
  })

  it.each([
    ['overview-count-sites', '/sites'],
    ['overview-count-repos', '/repos']
  ])('%s opens %s', async (testId, path) => {
    setup(overview())
    renderPage()
    await userEvent.click(await screen.findByTestId(testId))
    expect(screen.getByTestId('where')).toHaveTextContent(path)
  })

  it('the problems count moves the focus to the problem list', async () => {
    setup(overview())
    renderPage()
    await userEvent.click(await screen.findByTestId('overview-count-problems'))
    expect(screen.getByRole('region', { name: 'Problems' })).toHaveFocus()
  })

  it('a latest deploy opens the site detail; a failed deploy problem too', async () => {
    setup(overview())
    renderPage()
    const deploys = await screen.findAllByTestId('overview-deploy')
    await userEvent.click(within(deploys[0]).getByRole('link', { name: 'a.com' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/sites/a.com')
    await userEvent.click(within(screen.getByTestId('overview-problem')).getByRole('link', { name: 'b.com' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/sites/b.com')
  })

  it('a failing Hostinger shows its error only in its own parts; the GitHub parts stay filled', async () => {
    const limited = sourceError('HOSTINGER', 'RATE_LIMITED', 'Hostinger is limiting requests.', 23)
    setup(
      overview({
        sites: { count: null, errors: [limited] },
        problems: { items: [], errors: [limited] },
        latestDeploys: { deploys: [], errors: [limited] },
        sources: [CONNECTED[0], { source: 'HOSTINGER', state: 'LIMITED', message: 'Rate limited', retryAfterSeconds: 23 }]
      })
    )
    renderPage()
    expect(await screen.findByTestId('overview-count-sites-value')).toHaveTextContent('—')
    expect(screen.getByTestId('overview-count-repos-value')).toHaveTextContent('12')
    const errors = screen.getAllByTestId('overview-part-error')
    expect(errors).toHaveLength(3)
    for (const error of errors) expect(error).toHaveTextContent(/Rate limited, retrying in \d+ s/)
    expect(screen.getByText('Some checks could not be made, so the list may be incomplete.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('while rate limited it counts down, then asks again once', async () => {
    const limited = sourceError('HOSTINGER', 'RATE_LIMITED', 'Hostinger is limiting requests.', 3)
    const fake = setup(overview({ sites: { count: null, errors: [limited] } }), overview())
    fakeClock()
    renderPage()
    expect(await screen.findByTestId('overview-part-error')).toHaveTextContent('retrying in 3 s')
    await advance(1000)
    expect(screen.getByTestId('overview-part-error')).toHaveTextContent('retrying in 2 s')
    await advance(2100)
    expect(await screen.findByTestId('overview-count-sites-value')).toHaveTextContent('3')
    expect(fake.urls).toEqual([CACHED_URL, CACHED_URL])
  })

  it('a rejected GitHub token offers Reconnect in the GitHub parts', async () => {
    const rejected = sourceError('GITHUB', 'TOKEN_REJECTED', 'GitHub no longer accepts the saved token.')
    setup(
      overview({
        repos: { count: null, errors: [rejected] },
        sources: [{ source: 'GITHUB', state: 'ERROR', message: 'Token rejected', retryAfterSeconds: null }, CONNECTED[1]]
      })
    )
    renderPage()
    const error = await screen.findByTestId('overview-part-error')
    expect(error).toHaveTextContent('GitHub no longer accepts the saved token.')
    await userEvent.click(within(error).getByRole('button', { name: 'Reconnect' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/settings{"reconnect":true}')
  })

  it('the GitHub source row offers Reconnect when its token needs replacing', async () => {
    setup(overview({ sources: [{ source: 'GITHUB', state: 'ERROR', message: 'Token rejected', retryAfterSeconds: null }, CONNECTED[1]] }))
    renderPage()
    const row = await screen.findByTestId('overview-source-github')
    await userEvent.click(within(row).getByRole('button', { name: 'Reconnect' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/settings{"reconnect":true}')
  })

  it('with nothing connected it says so and links to Settings', async () => {
    const notConnected = (source: 'GITHUB' | 'HOSTINGER'): SourceStatus => ({ source, state: 'NOT_CONNECTED', message: null, retryAfterSeconds: null })
    setup(overview({ sources: [notConnected('GITHUB'), notConnected('HOSTINGER')] }))
    renderPage()
    const empty = await screen.findByTestId('overview-nothing-connected')
    expect(empty).toHaveTextContent('Nothing is connected yet')
    expect(screen.queryByTestId('overview-count-sites')).toBeNull()
    await userEvent.click(within(empty).getByRole('button', { name: 'Open Settings' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/settings')
  })

  it('a backend failure shows one error with Try again', async () => {
    const fake = setup(new Response(JSON.stringify({ status: 500, detail: 'Something broke' }), { status: 500 }), overview())
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Something broke')
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByTestId('overview-count-sites-value')).toHaveTextContent('3')
    expect(fake.urls).toEqual([CACHED_URL, FRESH_URL])
  })

  it('opening reuses the backend copy; Refresh asks the sources again', async () => {
    const fake = setup(overview(), overview({ fetchedAt: '2026-11-14T09:05:00Z' }))
    renderPage()
    await screen.findByTestId('overview-count-sites-value')
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await screen.findByText(/Last updated/)
    await vi.waitFor(() => expect(screen.getByText('Last updated').querySelector('time')).toHaveAttribute('dateTime', '2026-11-14T09:05:00Z'))
    expect(fake.urls).toEqual([CACHED_URL, FRESH_URL])
  })

  it('refreshes about every 30 s with the backend copy, and not while the window is hidden', async () => {
    const fake = setup(overview(), overview({ latestDeploys: { deploys: [deploy('c.com', 'IN_PROGRESS')], errors: [] } }))
    fakeClock()
    renderPage()
    await screen.findAllByTestId('overview-deploy')
    await advance(30_000)
    expect(await screen.findByText('c.com')).toBeInTheDocument()
    expect(fake.urls).toEqual([CACHED_URL, CACHED_URL])
    visibility = 'hidden'
    act(() => {
      document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
    })
    await advance(120_000)
    expect(fake.urls).toHaveLength(2)
  })

  it('stops refreshing when the view is left', async () => {
    const fake = setup(overview())
    fakeClock()
    const { unmount } = renderPage()
    await screen.findAllByTestId('overview-deploy')
    unmount()
    await advance(120_000)
    expect(fake.urls).toHaveLength(1)
  })
})
