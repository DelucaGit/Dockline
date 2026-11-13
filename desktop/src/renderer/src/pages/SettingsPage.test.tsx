import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ConnectionState, GithubConnectProgress } from '@/api/github'
import { App } from '@/App'
import { SettingsPage } from './SettingsPage'

const BASE_URL = 'http://127.0.0.1:5555'
const CODE = { userCode: 'WDJB-MJHT', verificationUri: 'https://github.com/login/device', expiresInSeconds: 900, intervalSeconds: 1 }

/** The only fakes: the backend's HTTP API (in memory) and the preload API. */
function fakeBackend(initial: { state: ConnectionState; message?: string | null } = { state: 'NOT_CONNECTED' }) {
  const github = { ...initial }
  const calls: string[] = []
  const polls: GithubConnectProgress[] = []
  let startProblem: { status: number; title: string; detail: string } | null = null
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const path = new URL(url).pathname
      calls.push(`${init?.method ?? 'GET'} ${path}`)
      const json = (body: unknown, status = 200) =>
        new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
      if (path === '/api/status') {
        return json({
          sources: [
            { source: 'GITHUB', state: github.state, message: github.message ?? null },
            { source: 'HOSTINGER', state: 'NOT_CONNECTED', message: null }
          ]
        })
      }
      if (path === '/api/github/connect') {
        return startProblem ? json(startProblem, startProblem.status) : json(CODE)
      }
      if (path === '/api/github/connect/poll') {
        const next = polls.length > 1 ? polls.shift()! : polls[0]
        if (next.state === 'CONNECTED') {
          github.state = 'CONNECTED'
          github.message = 'Connected as octo-test'
        }
        return json(next)
      }
      if (path === '/api/github/recheck') return json({ source: 'GITHUB', state: github.state })
      if (path === '/api/github/disconnect') {
        github.state = 'NOT_CONNECTED'
        github.message = null
        return new Response(null, { status: 204 })
      }
      return new Response('not found', { status: 404 })
    })
  )
  const openExternal = vi.fn(async () => {})
  window.dockline = { getBackendState: vi.fn(), retryBackend: vi.fn(), openExternal }
  return {
    calls,
    openExternal,
    pollsReturn: (...p: GithubConnectProgress[]) => polls.splice(0, polls.length, ...p),
    startFails: (p: { status: number; title: string; detail: string }) => (startProblem = p)
  }
}

function renderSettings() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SettingsPage baseUrl={BASE_URL} />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/overview']}>
        <App baseUrl={BASE_URL} />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('Settings: GitHub section', () => {
  it('offers "Connect to GitHub" when not connected', async () => {
    fakeBackend()
    renderSettings()
    expect(await screen.findByRole('button', { name: 'Connect to GitHub' })).toBeInTheDocument()
  })

  it('shows the code, opens the browser, copies the code, and turns to "Connected" once approved', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend()
    backend.pollsReturn({ state: 'PENDING', intervalSeconds: 1 }, { state: 'CONNECTED' })
    renderSettings()

    await user.click(await screen.findByRole('button', { name: 'Connect to GitHub' }))

    expect(await screen.findByText('WDJB-MJHT')).toBeInTheDocument()
    expect(screen.getByText('Waiting for approval…')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'https://github.com/login/device' })).toHaveAttribute(
      'href',
      'https://github.com/login/device'
    )
    expect(backend.openExternal).toHaveBeenCalledWith('https://github.com/login/device')

    await user.click(screen.getByRole('button', { name: 'Copy code' }))
    expect(await navigator.clipboard.readText()).toBe('WDJB-MJHT')
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()

    expect(await screen.findByText('Connected', { exact: true }, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.getByText('Connected as octo-test')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeInTheDocument()
    expect(screen.queryByText('WDJB-MJHT')).not.toBeInTheDocument()
  })

  it('Cancel stops waiting and goes back to the connect button', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend()
    backend.pollsReturn({ state: 'PENDING', intervalSeconds: 1 })
    renderSettings()
    await user.click(await screen.findByRole('button', { name: 'Connect to GitHub' }))
    await screen.findByText('WDJB-MJHT')

    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByText('WDJB-MJHT')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Connect to GitHub' })).toBeInTheDocument()
  })

  it.each([
    ['DENIED', 'You clicked Cancel on GitHub.'],
    ['EXPIRED', 'The code expired. Please try again.'],
    ['ERROR', 'GitHub answered something unexpected.']
  ] as const)('%s shows the backend message and a "Try again" button that starts over', async (state, message) => {
    const user = userEvent.setup()
    const backend = fakeBackend()
    backend.pollsReturn({ state, message })
    renderSettings()
    await user.click(await screen.findByRole('button', { name: 'Connect to GitHub' }))

    expect(await screen.findByText(message)).toBeInTheDocument()
    backend.pollsReturn({ state: 'PENDING', intervalSeconds: 1 })
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('WDJB-MJHT')).toBeInTheDocument()
    expect(screen.queryByText(message)).not.toBeInTheDocument()
  })

  it('shows the readable message when the client ID is not configured', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend()
    backend.startFails({
      status: 400,
      title: 'GitHub is not set up',
      detail: 'GitHub client ID is not configured. Set DOCKLINE_GITHUB_CLIENT_ID to the Client ID.'
    })
    renderSettings()
    await user.click(await screen.findByRole('button', { name: 'Connect to GitHub' }))

    expect(await screen.findByText(/GitHub client ID is not configured/)).toBeInTheDocument()
    expect(screen.getByText(/docs\/github-oauth-app-setup\.md/)).toBeInTheDocument()
    expect(backend.openExternal).not.toHaveBeenCalled()
  })

  it('disconnects only after the user confirms', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend({ state: 'CONNECTED', message: 'Connected as octo-test' })
    renderSettings()
    await user.click(await screen.findByRole('button', { name: 'Disconnect' }))

    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(backend.calls).not.toContain('POST /api/github/disconnect')

    await user.click(screen.getByRole('button', { name: 'Keep connected' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(backend.calls).not.toContain('POST /api/github/disconnect')

    await user.click(screen.getByRole('button', { name: 'Disconnect' }))
    await user.click(screen.getByRole('button', { name: 'Yes, disconnect' }))

    expect(await screen.findByRole('button', { name: 'Connect to GitHub' })).toBeInTheDocument()
    expect(backend.calls).toContain('POST /api/github/disconnect')
  })

  it('shows the backend message for a connection problem and offers Reconnect', async () => {
    fakeBackend({ state: 'ERROR', message: 'GitHub no longer accepts the token. Please reconnect.' })
    renderSettings()
    expect(await screen.findByText('GitHub no longer accepts the token. Please reconnect.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reconnect' })).toBeInTheDocument()
  })
})

describe('Sidebar connection dots', () => {
  it.each([
    ['CONNECTED', 'GitHub: connected', 'bg-green-500'],
    ['LIMITED', 'GitHub: limited', 'bg-yellow-500'],
    ['ERROR', 'GitHub: error — reconnect needed', 'bg-red-500'],
    ['NOT_CONNECTED', 'GitHub: not connected', 'bg-red-500']
  ] as const)('GitHub %s is announced as "%s"', async (state, name, colour) => {
    fakeBackend({ state })
    renderApp()
    const link = await screen.findByRole('link', { name })
    expect(link.querySelector('span[aria-hidden]')).toHaveClass(colour)
  })

  it('shows Hostinger too, and a click goes to Settings', async () => {
    const user = userEvent.setup()
    fakeBackend({ state: 'CONNECTED' })
    renderApp()
    expect(await screen.findByRole('link', { name: 'Hostinger: not connected' })).toBeInTheDocument()

    await user.click(await screen.findByRole('link', { name: 'GitHub: connected' }))

    expect(screen.getByRole('heading', { name: 'Settings' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Disconnect' })).toBeInTheDocument())
  })

  it.each(['CONNECTED', 'LIMITED', 'NOT_CONNECTED'] as const)('offers no sidebar "Reconnect" when GitHub is %s', async (state) => {
    fakeBackend({ state })
    renderApp()
    await waitFor(() => expect(screen.getByTestId('connection-github')).toHaveAttribute('data-state', state))
    expect(screen.queryByRole('button', { name: 'Reconnect GitHub' })).not.toBeInTheDocument()
  })

  it('"Reconnect" next to a red GitHub dot opens Settings and starts the device flow at once', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend({ state: 'ERROR', message: 'GitHub no longer accepts the token. Please reconnect.' })
    backend.pollsReturn({ state: 'PENDING', intervalSeconds: 1 })
    renderApp()

    await user.click(await screen.findByRole('button', { name: 'Reconnect GitHub' }))

    expect(await screen.findByText('WDJB-MJHT')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Settings' })).toBeInTheDocument()
    expect(backend.calls.filter((c) => c === 'POST /api/github/connect')).toHaveLength(1)
    expect(backend.openExternal).toHaveBeenCalledWith('https://github.com/login/device')
  })

  it('opening Settings rechecks the token right away', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend({ state: 'CONNECTED' })
    renderApp()
    await user.click(await screen.findByRole('link', { name: 'GitHub: connected' }))
    await waitFor(() => expect(backend.calls).toContain('POST /api/github/recheck'))
  })
})
