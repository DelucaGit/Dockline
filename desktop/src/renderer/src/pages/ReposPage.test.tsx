import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { GithubRepositories, GithubRepository } from '@/api/repos'
import { ReposPage } from './ReposPage'

const BASE_URL = 'http://127.0.0.1:5555'

function repo(name: string, overrides: Partial<GithubRepository> = {}): GithubRepository {
  return {
    name,
    fullName: `octo/${name}`,
    visibility: 'PUBLIC',
    lastPushAt: new Date(Date.now() - 3 * 3600 * 1000).toISOString(),
    htmlUrl: `https://github.com/octo/${name}`,
    fork: false,
    archived: false,
    ...overrides
  }
}

/** The only fakes: the backend's HTTP API and the Electron bridge. */
function setup(first: Partial<GithubRepositories> = {}) {
  let body: GithubRepositories = { repositories: [], fetchedAt: '2026-11-13T09:00:00Z', truncated: false, ...first }
  let release: (() => void) | undefined
  let hold = false
  let calls = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      calls++
      if (hold) await new Promise<void>((r) => (release = r))
      return new Response(JSON.stringify(body))
    })
  )
  const openExternal = vi.fn(async () => {})
  window.dockline = { getBackendState: vi.fn(), retryBackend: vi.fn(), openExternal }
  return {
    openExternal,
    calls: () => calls,
    set: (next: Partial<GithubRepositories>) => (body = { ...body, ...next }),
    holdNext: () => (hold = true),
    release: () => {
      hold = false
      release?.()
    }
  }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ReposPage baseUrl={BASE_URL} />
    </QueryClientProvider>
  )
}

describe('ReposPage', () => {
  beforeEach(() => vi.unstubAllGlobals())
  afterEach(() => vi.unstubAllGlobals())

  it('shows a loading state, then the repositories with badges and a readable push time', async () => {
    const fake = setup({
      repositories: [
        repo('site', { visibility: 'PRIVATE', fork: true }),
        repo('old', { archived: true, lastPushAt: '2020-01-01T00:00:00Z' })
      ]
    })
    fake.holdNext()
    renderPage()
    expect(screen.getByRole('status')).toHaveTextContent('Loading')
    fake.release()

    const rows = await screen.findAllByTestId('repo-row')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByRole('button', { name: 'octo/site' })).toBeInTheDocument()
    expect(within(rows[0]).getByText('Private')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Fork')).toBeInTheDocument()
    const time = within(rows[0]).getByText('3 hours ago')
    expect(time).toHaveAttribute('title', expect.stringMatching(/\d/))
    expect(within(rows[1]).getByText('Public')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Archived')).toBeInTheDocument()
    expect(within(rows[1]).getByText(/years? ago/)).toBeInTheDocument()
    expect(screen.queryByText(/first 5,000/)).not.toBeInTheDocument()
  })

  it('says "No pushes yet" for a repository nobody pushed to', async () => {
    setup({ repositories: [repo('fresh', { lastPushAt: null })] })
    renderPage()
    expect(await screen.findByText('No pushes yet')).toBeInTheDocument()
  })

  it('shows "No repositories" for an empty list', async () => {
    setup()
    renderPage()
    expect(await screen.findByText('No repositories')).toBeInTheDocument()
  })

  it('notes when the list was cut off', async () => {
    setup({ repositories: [repo('a')], truncated: true })
    renderPage()
    expect(await screen.findByText(/first 5,000 repositories/)).toBeInTheDocument()
  })

  it('Refresh fetches again, is disabled meanwhile and updates "Last updated"', async () => {
    const user = userEvent.setup()
    const fake = setup({ repositories: [repo('a')] })
    renderPage()
    await screen.findByText('octo/a')
    const label = () => screen.getByText(/Last updated/)
    const before = label().textContent

    fake.set({ repositories: [repo('a'), repo('b')], fetchedAt: '2026-11-13T09:30:45Z' })
    fake.holdNext()
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeDisabled()
    fake.release()

    expect(await screen.findByText('octo/b')).toBeInTheDocument()
    expect(label().textContent).not.toBe(before)
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeEnabled()
    expect(fake.calls()).toBe(2)
  })

  it('opens the repository on GitHub through the app bridge', async () => {
    const user = userEvent.setup()
    const fake = setup({ repositories: [repo('site')] })
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'octo/site' }))
    expect(fake.openExternal).toHaveBeenCalledWith('https://github.com/octo/site')
  })

  it('shows the server message with a "Try again" that retries', async () => {
    const user = userEvent.setup()
    const fake = setup({ repositories: [repo('a')] })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(new Response(JSON.stringify({ detail: 'GitHub is down' }), { status: 502 }))
        .mockImplementation(async () => new Response(JSON.stringify({ repositories: [repo('a')], fetchedAt: 'x', truncated: false })))
    )
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('GitHub is down')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('octo/a')).toBeInTheDocument()
    expect(fake.openExternal).not.toHaveBeenCalled()
  })
})
