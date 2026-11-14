import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SiteDeploy, SiteDeploys } from '@/api/deploys'
import type { GithubRepositories } from '@/api/repos'
import { siteLinksKey, type SiteDetail } from '@/api/sites'
import { SiteDetailPage } from './SiteDetailPage'

const BASE_URL = 'http://127.0.0.1:5555'
const SITE_URL = `${BASE_URL}/api/sites/a.com`

const repoRef = (fullName: string) => ({ fullName, htmlUrl: `https://github.com/${fullName}` })

function detail(source: SiteDetail['source'], fullName: string | null, extra: Partial<SiteDetail> = {}): SiteDetail {
  return {
    domain: 'a.com',
    repo: fullName ? repoRef(fullName) : null,
    source,
    hostingerChecked: true,
    repoWarning: null,
    problems: [],
    ...extra
  }
}

function repositories(...names: string[]): GithubRepositories {
  return {
    repositories: names.map((fullName) => ({
      fullName,
      htmlUrl: `https://github.com/${fullName}`,
      visibility: 'PUBLIC' as const,
      fork: false,
      archived: false,
      lastPushAt: null
    })),
    fetchedAt: '2026-11-13T09:00:00Z',
    truncated: false
  } as GithubRepositories
}

const problem = (status: number, code: string, detailText: string) =>
  new Response(JSON.stringify({ status, code, detail: detailText }), { status })

type Answer = object | Response

interface Fake {
  /** GET /api/sites/a.com answers, consumed in order; the last one repeats. */
  site: Answer[]
  repos?: Answer
  put?: Answer
  del?: Response
  /** GET /api/sites/deploys answers, consumed in order; the last one repeats. "No deploys" by default. */
  deploys?: Answer[]
}

const DEPLOYS_URL = `${BASE_URL}/api/sites/deploys?refresh=false`

const deploys = (...list: SiteDeploy[]): SiteDeploys => ({ deploys: list, fetchedAt: '2026-11-14T09:00:00Z' })

const deploy = (status: SiteDeploy['status'], extra: Partial<SiteDeploy> = {}): SiteDeploy => ({
  domain: 'A.com',
  status,
  at: '2024-05-29T05:51:02Z',
  commit: { sha: 'a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0', message: 'Initial commit' },
  source: 'HOSTINGER',
  problems: [],
  ...extra
})

/** The only fakes: the backend's HTTP API and the preload bridge. `deployUrls` holds the calls for the deploys. */
function setup(fake: Fake) {
  const calls: { method: string; url: string; body?: unknown }[] = []
  const deployUrls: string[] = []
  const deployAnswers = fake.deploys ?? [deploys()]
  const openExternal = vi.fn(async () => {})
  window.dockline = { getBackendState: vi.fn(), retryBackend: vi.fn(), openExternal }
  let siteCalls = 0
  const answer = (a: Answer | undefined) =>
    a === undefined ? new Response('unexpected', { status: 500 }) : a instanceof Response ? a.clone() : new Response(JSON.stringify(a))
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/api/sites/deploys')) {
        deployUrls.push(url)
        return answer(deployAnswers[Math.min(deployUrls.length - 1, deployAnswers.length - 1)])
      }
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      if (url.endsWith('/api/github/repositories')) return answer(fake.repos)
      if (url.endsWith('/repo')) return method === 'PUT' ? answer(fake.put) : (fake.del ?? new Response(null, { status: 204 }))
      if (url.includes('/api/sites/a.com')) return answer(fake.site[Math.min(siteCalls++, fake.site.length - 1)])
      return answer(undefined)
    })
  )
  return { calls, deployUrls, openExternal, urls: (method = 'GET') => calls.filter((c) => c.method === method).map((c) => c.url) }
}

function Where() {
  const loc = useLocation()
  return <p data-testid="where">{loc.pathname + (loc.state ? JSON.stringify(loc.state) : '')}</p>
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retryDelay: 0 } } })
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  const view = render(
    <MemoryRouter initialEntries={['/sites/a.com']}>
      <QueryClientProvider client={client}>
        <Routes>
          <Route path="/sites/:domain" element={<SiteDetailPage baseUrl={BASE_URL} />} />
          <Route path="*" element={null} />
        </Routes>
        <Where />
      </QueryClientProvider>
    </MemoryRouter>
  )
  const linksInvalidated = () =>
    invalidate.mock.calls.some(([filters]) => JSON.stringify(filters?.queryKey) === JSON.stringify(siteLinksKey(BASE_URL)))
  return { linksInvalidated, unmount: view.unmount }
}

describe('SiteDetailPage', () => {
  beforeEach(() => vi.unstubAllGlobals())
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows the domain, a back link to Sites and Refresh', async () => {
    const user = userEvent.setup()
    setup({ site: [detail('NONE', null)] })
    renderPage()
    expect(await screen.findByRole('heading', { name: 'a.com' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Back to Sites' }))
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/sites$/)
  })

  it('a Git-connected site shows its repo "from Hostinger" without Link, Change or Remove', async () => {
    setup({ site: [detail('HOSTINGER', 'octo/site')] })
    renderPage()
    expect(await screen.findByRole('button', { name: 'octo/site' })).toBeInTheDocument()
    expect(screen.getByTestId('site-repo-source')).toHaveTextContent('from Hostinger')
    for (const name of ['Link repo', 'Change', 'Remove']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
  })

  it('a manually linked site shows its repo "linked manually" with Change and Remove', async () => {
    setup({ site: [detail('MANUAL', 'octo/site')] })
    renderPage()
    expect(await screen.findByRole('button', { name: 'octo/site' })).toBeInTheDocument()
    expect(screen.getByTestId('site-repo-source')).toHaveTextContent('linked manually')
    expect(screen.getByRole('button', { name: 'Change' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Link repo' })).not.toBeInTheDocument()
  })

  it('a site without a link says "No repo linked" and offers Link repo', async () => {
    setup({ site: [detail('NONE', null)] })
    renderPage()
    expect(await screen.findByText('No repo linked')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Link repo' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument()
  })

  it('the repo name opens the repo on GitHub', async () => {
    const user = userEvent.setup()
    const fake = setup({ site: [detail('HOSTINGER', 'octo/site')] })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'octo/site' }))
    expect(fake.openExternal).toHaveBeenCalledWith('https://github.com/octo/site')
  })

  it('Link repo lists the GitHub repositories, filters them by name and saves the picked one', async () => {
    const user = userEvent.setup()
    const fake = setup({
      site: [detail('NONE', null)],
      repos: repositories('octo/alpha', 'octo/bravo', 'octo/charlie'),
      put: detail('MANUAL', 'octo/bravo')
    })
    const page = renderPage()
    await user.click(await screen.findByRole('button', { name: 'Link repo' }))

    const list = await screen.findByRole('list', { name: 'Your GitHub repositories' })
    expect(within(list).getAllByRole('button')).toHaveLength(3)
    await user.type(screen.getByRole('searchbox', { name: 'Filter repositories' }), 'BRA')
    expect(within(list).getAllByRole('button')).toHaveLength(1)
    await user.click(within(list).getByRole('button', { name: 'octo/bravo' }))

    expect(await screen.findByTestId('site-repo')).toHaveTextContent('octo/bravo')
    expect(screen.getByTestId('site-repo-source')).toHaveTextContent('linked manually')
    expect(screen.queryByTestId('repo-picker')).not.toBeInTheDocument()
    expect(fake.calls.find((c) => c.method === 'PUT')).toEqual({
      method: 'PUT',
      url: `${SITE_URL}/repo`,
      body: { repoFullName: 'octo/bravo' }
    })
    expect(page.linksInvalidated()).toBe(true)
    expect(fake.urls()).toEqual([SITE_URL, `${BASE_URL}/api/github/repositories`]) // the PUT answer is shown as is
  })

  it('a filter that matches nothing says so; Cancel closes the picker without saving', async () => {
    const user = userEvent.setup()
    const fake = setup({ site: [detail('NONE', null)], repos: repositories('octo/alpha') })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Link repo' }))
    await user.type(await screen.findByRole('searchbox', { name: 'Filter repositories' }), 'zzz')
    expect(screen.getByText('No repository matches the filter.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByTestId('repo-picker')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Link repo' })).toBeInTheDocument()
    expect(fake.urls('PUT')).toHaveLength(0)
  })

  it('Change picks another repo and marks the current one', async () => {
    const user = userEvent.setup()
    const fake = setup({
      site: [detail('MANUAL', 'octo/alpha')],
      repos: repositories('octo/alpha', 'octo/bravo'),
      put: detail('MANUAL', 'octo/bravo')
    })
    const page = renderPage()
    await user.click(await screen.findByRole('button', { name: 'Change' }))
    const list = await screen.findByRole('list', { name: 'Your GitHub repositories' })
    expect(within(list).getByRole('button', { name: /octo\/alpha/ })).toHaveAttribute('aria-current', 'true')
    await user.click(within(list).getByRole('button', { name: 'octo/bravo' }))
    await waitFor(() => expect(screen.getByTestId('site-repo')).toHaveTextContent('octo/bravo'))
    expect(fake.calls.find((c) => c.method === 'PUT')?.body).toEqual({ repoFullName: 'octo/bravo' })
    expect(page.linksInvalidated()).toBe(true)
  })

  it('a refused save shows the backend message and keeps the picker open', async () => {
    const user = userEvent.setup()
    setup({
      site: [detail('NONE', null)],
      repos: repositories('octo/alpha'),
      put: problem(409, 'SITE_GIT_CONNECTED', 'a.com is Git-connected in Hostinger; change the repository there.')
    })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Link repo' }))
    await user.click(await screen.findByRole('button', { name: 'octo/alpha' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('a.com is Git-connected in Hostinger; change the repository there.')
    expect(screen.getByTestId('repo-picker')).toBeInTheDocument()
    expect(screen.getByText('No repo linked')).toBeInTheDocument()
  })

  it('Remove asks first; "Keep link" removes nothing', async () => {
    const user = userEvent.setup()
    const fake = setup({ site: [detail('MANUAL', 'octo/site')] })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Remove' }))
    expect(screen.getByRole('alertdialog', { name: 'Remove the link to octo/site?' })).toBeInTheDocument()
    expect(fake.urls('DELETE')).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Keep link' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'octo/site' })).toBeInTheDocument()
    expect(fake.urls('DELETE')).toHaveLength(0)
  })

  it('confirming Remove deletes the link and shows "No repo linked"', async () => {
    const user = userEvent.setup()
    const fake = setup({ site: [detail('MANUAL', 'octo/site'), detail('NONE', null)] })
    const page = renderPage()
    await user.click(await screen.findByRole('button', { name: 'Remove' }))
    await user.click(screen.getByRole('button', { name: 'Yes, remove' }))
    expect(await screen.findByText('No repo linked')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Link repo' })).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(fake.urls('DELETE')).toEqual([`${SITE_URL}/repo`])
    expect(page.linksInvalidated()).toBe(true)
  })

  it('a failed Remove keeps the link and says so', async () => {
    const user = userEvent.setup()
    setup({ site: [detail('MANUAL', 'octo/site')], del: problem(500, 'SETTINGS_UNAVAILABLE', 'The settings file could not be written.') })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Remove' }))
    await user.click(screen.getByRole('button', { name: 'Yes, remove' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The settings file could not be written.')
    expect(screen.getByRole('button', { name: 'octo/site' })).toBeInTheDocument()
  })

  it('a missing repo is a warning, not an error state', async () => {
    setup({
      site: [
        detail('MANUAL', 'octo/gone', {
          repoWarning: { code: 'REPO_NOT_FOUND', message: 'octo/gone was not found on GitHub.' }
        })
      ]
    })
    renderPage()
    expect(await screen.findByTestId('repo-warning')).toHaveTextContent('octo/gone was not found on GitHub.')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'octo/gone' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Change' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument()
  })

  it('when GitHub is not connected, Link repo explains it and leads to Settings', async () => {
    const user = userEvent.setup()
    setup({ site: [detail('NONE', null)], repos: problem(409, 'GITHUB_NOT_CONNECTED', 'GitHub is not connected.') })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Link repo' }))
    expect(await screen.findByText('GitHub must be connected first')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Open Settings' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/settings')
  })

  it('when the repositories cannot be loaded, the picker shows the reason and Try again', async () => {
    const user = userEvent.setup()
    const fake = setup({ site: [detail('NONE', null)], repos: problem(502, 'GITHUB_UNAVAILABLE', 'GitHub could not be reached.') })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Link repo' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('GitHub could not be reached.')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(fake.urls().filter((u) => u.endsWith('/repositories'))).toHaveLength(2))
  })

  it('Hostinger down: the manual link is still shown, with a note that the Git connection was not checked', async () => {
    setup({
      site: [
        detail('MANUAL', 'octo/site', {
          hostingerChecked: false,
          problems: [{ source: 'HOSTINGER', code: 'HOSTINGER_UNAVAILABLE', message: 'Hostinger could not be reached.', retryAfterSeconds: null }]
        })
      ]
    })
    renderPage()
    const note = await screen.findByTestId('site-problem-hostinger')
    expect(note).toHaveTextContent('The Hostinger Git connection could not be checked.')
    expect(note).toHaveTextContent('Hostinger could not be reached.')
    expect(screen.getByRole('button', { name: 'octo/site' })).toBeInTheDocument()
    expect(screen.getByTestId('site-repo-source')).toHaveTextContent('linked manually')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByTestId('site-problem-github')).not.toBeInTheDocument()
  })

  it('GitHub down: the link is shown, with a note that it could not be verified', async () => {
    setup({
      site: [
        detail('HOSTINGER', 'octo/site', {
          problems: [{ source: 'GITHUB', code: 'GITHUB_UNAVAILABLE', message: 'GitHub could not be reached.', retryAfterSeconds: null }]
        })
      ]
    })
    renderPage()
    const note = await screen.findByTestId('site-problem-github')
    expect(note).toHaveTextContent('The linked repo could not be verified on GitHub.')
    expect(note).toHaveTextContent('GitHub could not be reached.')
    expect(screen.getByRole('button', { name: 'octo/site' })).toBeInTheDocument()
    expect(screen.getByTestId('site-repo-source')).toHaveTextContent('from Hostinger')
    expect(screen.queryByTestId('site-problem-hostinger')).not.toBeInTheDocument()
  })

  it('an unknown site says "Site not found" with a way back, without asking again', async () => {
    const user = userEvent.setup()
    const fake = setup({ site: [problem(404, 'SITE_NOT_FOUND', 'Hostinger has no website a.com.')] })
    renderPage()
    expect(await screen.findByText('Site not found')).toBeInTheDocument()
    expect(fake.urls()).toEqual([SITE_URL])
    await user.click(screen.getByRole('button', { name: 'Back to Sites' }))
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/sites$/)
  })

  it('opening reuses the backend copy; Refresh asks Hostinger again', async () => {
    const user = userEvent.setup()
    const fake = setup({ site: [detail('NONE', null), detail('HOSTINGER', 'octo/site')] })
    renderPage()
    await screen.findByText('No repo linked')
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByRole('button', { name: 'octo/site' })).toBeInTheDocument()
    expect(fake.urls()).toEqual([SITE_URL, `${SITE_URL}?refresh=true`])
  })

  it('a failed load shows a readable message and Try again', async () => {
    const user = userEvent.setup()
    // Four failures: the first try and the three automatic retries.
    const down = problem(500, 'INTERNAL', 'Something went wrong.')
    setup({ site: [down, down, down, down, detail('NONE', null)] })
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong.')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('No repo linked')).toBeInTheDocument()
  })

  describe('latest deploy', () => {
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
    // Only what the automatic refresh and the countdown use; fetch and the test's own waits stay real.
    const fakeClock = () => vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
    const card = () => screen.getByTestId('site-deploy')

    beforeEach(() => {
      visibility = 'visible'
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility })
      vi.spyOn(document, 'hasFocus').mockReturnValue(true)
    })
    afterEach(() => {
      Reflect.deleteProperty(document, 'visibilityState')
      vi.restoreAllMocks()
    })

    it('shows badge, time, short commit, source and "Last updated" for a succeeded deploy', async () => {
      setup({ site: [detail('NONE', null)], deploys: [deploys(deploy('SUCCEEDED'))] })
      renderPage()
      const badge = await screen.findByTestId('deploy-badge')
      expect(within(card()).getByRole('heading', { name: 'Latest deploy' })).toBeInTheDocument()
      expect(card()).toContainElement(badge)
      expect(badge).toHaveTextContent('Succeeded')
      const time = screen.getByTestId('deploy-status').querySelector('time')
      expect(time).toHaveAttribute('datetime', '2024-05-29T05:51:02Z')
      expect(time).toHaveTextContent(/ago$/)
      expect(screen.getByTestId('deploy-commit')).toHaveTextContent(/^a1b2c3d$/)
      expect(screen.getByTestId('deploy-commit')).toHaveAttribute('title', 'Initial commit')
      expect(screen.getByTestId('deploy-status')).toHaveTextContent('Hostinger')
      expect(screen.getByTestId('deploy-fetched-at')).toHaveAttribute('datetime', '2026-11-14T09:00:00Z')
      expect(screen.queryByTestId('deploy-trouble')).not.toBeInTheDocument()
    })

    it('a site that is not a Node.js website, or has no entry, shows "No deploy information"', async () => {
      setup({ site: [detail('NONE', null)], deploys: [deploys(deploy('NONE', { at: null, commit: null, source: 'NONE' }))] })
      const first = renderPage()
      expect(await screen.findByText('No deploy information')).toBeInTheDocument()
      expect(card()).toHaveTextContent('No deploy information')
      expect(screen.queryByTestId('deploy-badge')).not.toBeInTheDocument()
      first.unmount()

      setup({ site: [detail('NONE', null)], deploys: [deploys(deploy('SUCCEEDED', { domain: 'other.com' }))] })
      renderPage()
      expect(await screen.findByText('No deploy information')).toBeInTheDocument()
      expect(card()).toHaveTextContent('No deploy information')
      expect(screen.queryByTestId('deploy-badge')).not.toBeInTheDocument()
    })

    it('a status that changes between two automatic refreshes updates without a click', async () => {
      const fake = setup({ site: [detail('NONE', null)], deploys: [deploys(deploy('IN_PROGRESS')), deploys(deploy('SUCCEEDED'))] })
      fakeClock()
      renderPage()
      expect(await screen.findByText('In progress')).toBeInTheDocument()
      await advance(29_000)
      expect(fake.deployUrls).toEqual([DEPLOYS_URL])
      await advance(1_000)
      expect(await screen.findByText('Succeeded')).toBeInTheDocument()
      expect(screen.queryByText('In progress')).not.toBeInTheDocument()
      expect(fake.deployUrls).toEqual([DEPLOYS_URL, DEPLOYS_URL])
      expect(fake.urls()).toEqual([SITE_URL]) // the site itself is not asked for again
    })

    it('does not refresh while the window is hidden or without focus, nor after the view is left', async () => {
      const fake = setup({ site: [detail('NONE', null)], deploys: [deploys(deploy('IN_PROGRESS')), deploys(deploy('SUCCEEDED'))] })
      fakeClock()
      const page = renderPage()
      await screen.findByText('In progress')
      setVisibility('hidden')
      await advance(120_000)
      expect(fake.deployUrls).toHaveLength(1)
      setVisibility('visible')
      expect(await screen.findByText('Succeeded')).toBeInTheDocument()
      expect(fake.deployUrls).toHaveLength(2)
      act(() => {
        window.dispatchEvent(new Event('blur'))
      })
      await advance(120_000)
      expect(fake.deployUrls).toHaveLength(2)
      act(() => {
        window.dispatchEvent(new Event('focus'))
      })
      await waitFor(() => expect(fake.deployUrls).toHaveLength(3))
      page.unmount()
      await advance(120_000)
      expect(fake.deployUrls).toHaveLength(3)
    })

    it('Refresh asks for the site and the deploys again with refresh=true', async () => {
      const user = userEvent.setup()
      const fake = setup({ site: [detail('NONE', null)], deploys: [deploys(deploy('IN_PROGRESS')), deploys(deploy('FAILED'))] })
      renderPage()
      await screen.findByText('In progress')
      await user.click(screen.getByRole('button', { name: 'Refresh' }))
      expect(await screen.findByText('Failed')).toBeInTheDocument()
      expect(fake.deployUrls).toEqual([DEPLOYS_URL, `${BASE_URL}/api/sites/deploys?refresh=true`])
      expect(fake.urls()).toEqual([SITE_URL, `${SITE_URL}?refresh=true`])
    })

    it('while rate limited it counts down, keeps the last status, waits, then asks again', async () => {
      const limited = new Response(
        JSON.stringify({ status: 429, code: 'HOSTINGER_RATE_LIMITED', detail: 'Slow down', retryAfterSeconds: 60 }),
        { status: 429 }
      )
      const fake = setup({
        site: [detail('NONE', null)],
        deploys: [deploys(deploy('IN_PROGRESS')), limited, deploys(deploy('SUCCEEDED'))]
      })
      fakeClock()
      renderPage()
      await screen.findByText('In progress')
      await advance(30_000)
      const note = await screen.findByTestId('deploy-trouble')
      expect(note).toHaveTextContent('Deploy status: rate limited, retrying in 60 s')
      expect(note).toHaveAttribute('role', 'status')
      expect(screen.getByText('In progress')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      await advance(45_000) // longer than the normal interval: still waiting
      expect(screen.getByTestId('deploy-trouble')).toHaveTextContent('retrying in 15 s')
      expect(fake.deployUrls).toHaveLength(2)
      await advance(15_000)
      expect(await screen.findByText('Succeeded')).toBeInTheDocument()
      expect(fake.deployUrls).toHaveLength(3)
      await waitFor(() => expect(screen.queryByTestId('deploy-trouble')).not.toBeInTheDocument())
      await advance(30_000) // back to the normal rhythm
      expect(fake.deployUrls).toHaveLength(4)
    })

    it('a failed update says so with the backend message, keeps the last status and keeps refreshing', async () => {
      const fake = setup({
        site: [detail('MANUAL', 'octo/site')],
        deploys: [deploys(deploy('SUCCEEDED')), problem(502, 'HOSTINGER_UNAVAILABLE', 'Hostinger did not answer.'), deploys(deploy('FAILED'))]
      })
      fakeClock()
      renderPage()
      await screen.findByText('Succeeded')
      await advance(30_000)
      const note = await screen.findByTestId('deploy-trouble')
      expect(note).toHaveTextContent('Deploy status could not be updated. Hostinger did not answer.')
      expect(screen.getByText('Succeeded')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.getByTestId('site-repo')).toHaveTextContent('octo/site') // the rest of the view still works
      await advance(30_000)
      expect(await screen.findByText('Failed')).toBeInTheDocument()
      expect(fake.deployUrls).toHaveLength(3)
      await waitFor(() => expect(screen.queryByTestId('deploy-trouble')).not.toBeInTheDocument())
    })

    it('a deploys call that fails from the start shows only the note; the site is still shown', async () => {
      setup({ site: [detail('MANUAL', 'octo/site')], deploys: [problem(502, 'HOSTINGER_UNAVAILABLE', 'Hostinger did not answer.')] })
      renderPage()
      expect(await screen.findByTestId('deploy-trouble')).toHaveTextContent('Deploy status could not be updated.')
      expect(screen.queryByTestId('deploy-status')).not.toBeInTheDocument()
      expect(screen.getByTestId('site-repo')).toHaveTextContent('octo/site')
    })
  })
})
