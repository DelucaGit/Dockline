import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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
}

/** The only fakes: the backend's HTTP API and the preload bridge. */
function setup(fake: Fake) {
  const calls: { method: string; url: string; body?: unknown }[] = []
  const openExternal = vi.fn(async () => {})
  window.dockline = { getBackendState: vi.fn(), retryBackend: vi.fn(), openExternal }
  let siteCalls = 0
  const answer = (a: Answer | undefined) =>
    a === undefined ? new Response('unexpected', { status: 500 }) : a instanceof Response ? a.clone() : new Response(JSON.stringify(a))
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      if (url.endsWith('/api/github/repositories')) return answer(fake.repos)
      if (url.endsWith('/repo')) return method === 'PUT' ? answer(fake.put) : (fake.del ?? new Response(null, { status: 204 }))
      if (url.includes('/api/sites/a.com')) return answer(fake.site[Math.min(siteCalls++, fake.site.length - 1)])
      return answer(undefined)
    })
  )
  return { calls, openExternal, urls: (method = 'GET') => calls.filter((c) => c.method === method).map((c) => c.url) }
}

function Where() {
  const loc = useLocation()
  return <p data-testid="where">{loc.pathname + (loc.state ? JSON.stringify(loc.state) : '')}</p>
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retryDelay: 0 } } })
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  render(
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
  return { linksInvalidated }
}

describe('SiteDetailPage', () => {
  beforeEach(() => vi.unstubAllGlobals())
  afterEach(() => vi.unstubAllGlobals())

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
})
