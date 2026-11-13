import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ConnectionState } from '@/api/github'
import { Connections } from '@/components/Connections'
import { SettingsPage } from './SettingsPage'

const BASE_URL = 'http://127.0.0.1:5555'
const MESSAGE = 'Hostinger no longer accepts the saved token (revoked or expired). Replace the token in Settings.'

/** The only fake: the backend's HTTP API. A new token is valid when it ends in "NEW". */
function fakeBackend(initial: ConnectionState) {
  let state = initial
  const calls: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const path = new URL(url).pathname
      calls.push(`${init?.method ?? 'GET'} ${path}`)
      const json = (body: unknown, status = 200) =>
        new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/problem+json' } })
      if (path === '/api/status') {
        return json({
          sources: [
            { source: 'GITHUB', state: 'NOT_CONNECTED', message: null },
            { source: 'HOSTINGER', state, message: state === 'ERROR' ? MESSAGE : null }
          ]
        })
      }
      if (path === '/api/hostinger/token' && init?.method === 'PUT') {
        if (!String(init.body).endsWith('NEW"}')) {
          return json({ status: 422, title: 'Invalid token', detail: 'Hostinger rejected this token.' }, 422)
        }
        state = 'CONNECTED'
        return json({ source: 'HOSTINGER', state, message: null })
      }
      return json({ source: 'HOSTINGER', state })
    })
  )
  window.dockline = { getBackendState: vi.fn(), retryBackend: vi.fn(), openExternal: vi.fn(async () => {}) }
  return { calls }
}

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Connections baseUrl={BASE_URL} />
        <Routes>
          <Route path="/" element={<p>Start</p>} />
          <Route path="/settings" element={<SettingsPage baseUrl={BASE_URL} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('replacing the Hostinger token', () => {
  it('shows "Replace token" in the sidebar only for ERROR, with a red dot and its own label', async () => {
    fakeBackend('ERROR')
    renderApp()

    expect(await screen.findByRole('button', { name: 'Replace Hostinger token' })).toBeInTheDocument()
    const link = screen.getByRole('link', { name: 'Hostinger: error — token needs replacing' })
    expect(link.querySelector('span[aria-hidden]')).toHaveClass('bg-red-500')
  })

  it.each(['NOT_CONNECTED', 'LIMITED', 'CONNECTED'] as const)('has no sidebar button in %s', async (state) => {
    fakeBackend(state)
    renderApp()

    await waitFor(() => expect(screen.getByTestId('connection-hostinger')).toHaveAttribute('data-state', state))
    expect(screen.queryByRole('button', { name: 'Replace Hostinger token' })).not.toBeInTheDocument()
  })

  it('the sidebar button opens Settings with the token field focused', async () => {
    fakeBackend('ERROR')
    renderApp()

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Replace Hostinger token' }))

    const field = await screen.findByLabelText('API token')
    await waitFor(() => expect(field).toHaveFocus())
    expect(screen.getByRole('heading', { name: 'Replace token' })).toBeInTheDocument()
  })

  it('an invalid new token is rejected with the message and the ERROR state stays', async () => {
    const user = userEvent.setup()
    fakeBackend('ERROR')
    renderApp()
    await user.click(await screen.findByTestId('connection-hostinger'))

    await user.type(await screen.findByLabelText('API token'), 'hstg_FAKE_BAD_TOKEN')
    await user.click(screen.getByRole('button', { name: 'Replace token' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Hostinger rejected this token.')
    expect(screen.getByText(MESSAGE)).toBeInTheDocument()
    expect(screen.getByTestId('connection-hostinger')).toHaveAttribute('data-state', 'ERROR')
  })

  it('a valid new token replaces the old one: connected, error text gone', async () => {
    const user = userEvent.setup()
    fakeBackend('ERROR')
    renderApp()
    await user.click(await screen.findByTestId('connection-hostinger'))

    await user.type(await screen.findByLabelText('API token'), 'hstg_FAKE_NEW')
    await user.click(screen.getByRole('button', { name: 'Replace token' }))

    await waitFor(() => expect(screen.getByTestId('connection-hostinger')).toHaveAttribute('data-state', 'CONNECTED'))
    expect(screen.queryByText(MESSAGE)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Replace Hostinger token' })).not.toBeInTheDocument()
  })

  it('rechecks Hostinger when the window gets focus', async () => {
    const { calls } = fakeBackend('CONNECTED')
    renderApp()
    await screen.findByTestId('connection-hostinger')

    window.dispatchEvent(new Event('focus'))

    await waitFor(() => expect(calls).toContain('POST /api/hostinger/recheck'))
  })
})
