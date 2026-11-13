import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BackendState, DocklineApi } from '../../../shared/backend'
import { App } from '../App'
import { BackendGate } from './BackendGate'
import { BackendStatus } from './BackendStatus'

const BASE_URL = 'http://127.0.0.1:5555'

// The only fakes: the preload API (Electron boundary) and the backend's HTTP endpoint.
function fakeDockline(states: BackendState[], retryResult?: BackendState) {
  let i = 0
  const api: DocklineApi = {
    getBackendState: vi.fn(async () => states[Math.min(i++, states.length - 1)]),
    retryBackend: vi.fn(async () => retryResult ?? { status: 'starting' as const })
  }
  window.dockline = api
  return api
}

function fakeHealth(status: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      expect(url).toBe(`${BASE_URL}/actuator/health`)
      return new Response(JSON.stringify({ status }))
    })
  )
}

function renderGate() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <BackendGate>
          {(baseUrl) => <App footer={<BackendStatus baseUrl={baseUrl} />} />}
        </BackendGate>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('backend start-up', () => {
  beforeEach(() => fakeHealth('UP'))
  afterEach(() => vi.unstubAllGlobals())

  it('shows a loading screen while the backend starts, then the app with "Backend connected"', async () => {
    fakeDockline([{ status: 'starting' }, { status: 'ready', baseUrl: BASE_URL }])
    renderGate()

    expect(screen.getByText('Starting Dockline…')).toBeInTheDocument()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()

    expect(await screen.findByText('Backend connected', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Main menu' })).toBeInTheDocument()
    expect(screen.queryByText('Starting Dockline…')).not.toBeInTheDocument()
  })

  it('shows a readable error with the reason when the backend failed to start', async () => {
    fakeDockline([{ status: 'error', message: 'Java was not found ("java").' }])
    renderGate()

    expect(await screen.findByRole('alert')).toHaveTextContent('The backend could not be started')
    expect(screen.getByRole('alert')).toHaveTextContent('Java was not found ("java").')
    expect(screen.queryByText('Backend connected')).not.toBeInTheDocument()
  })

  it('lets the user retry after an error', async () => {
    const user = userEvent.setup()
    const api = fakeDockline([{ status: 'error', message: 'Timed out' }], {
      status: 'ready',
      baseUrl: BASE_URL
    })
    renderGate()

    await user.click(await screen.findByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('Backend connected')).toBeInTheDocument()
    expect(api.retryBackend).toHaveBeenCalledOnce()
  })

  it('says so when the health endpoint does not report UP', async () => {
    fakeHealth('DOWN')
    fakeDockline([{ status: 'ready', baseUrl: BASE_URL }])
    renderGate()

    expect(await screen.findByText('Backend not responding')).toBeInTheDocument()
    expect(screen.queryByText('Backend connected')).not.toBeInTheDocument()
  })
})
