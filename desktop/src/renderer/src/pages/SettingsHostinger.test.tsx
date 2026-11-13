import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ConnectionState } from '@/api/github'
import { SettingsPage } from './SettingsPage'

const BASE_URL = 'http://127.0.0.1:5555'
const TOKEN = 'hstg_FAKE_TEST_TOKEN_123'

type Problem = { status: number; title: string; detail: string; retryAfter?: string }

/** The only fakes: the backend's HTTP API (in memory) and the preload API. */
function fakeBackend(initial: { state: ConnectionState; message?: string | null } = { state: 'NOT_CONNECTED' }) {
  const hostinger = { ...initial }
  const calls: string[] = []
  const bodies: string[] = []
  let saveProblem: Problem | null = null
  let release: (() => void) | null = null
  let hold = false
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
            { source: 'HOSTINGER', state: hostinger.state, message: hostinger.message ?? null }
          ]
        })
      }
      if (path === '/api/hostinger/token' && init?.method === 'PUT') {
        bodies.push(String(init.body))
        if (hold) await new Promise<void>((r) => (release = r))
        if (saveProblem) return json(saveProblem, saveProblem.status)
        hostinger.state = 'CONNECTED'
        hostinger.message = null
        return json({ source: 'HOSTINGER', state: 'CONNECTED', message: null })
      }
      if (path === '/api/hostinger/token' && init?.method === 'DELETE') {
        hostinger.state = 'NOT_CONNECTED'
        hostinger.message = null
        return new Response(null, { status: 204 })
      }
      if (path === '/api/hostinger/recheck') return json({ source: 'HOSTINGER', state: hostinger.state })
      return new Response('not found', { status: 404 })
    })
  )
  const openExternal = vi.fn(async () => {})
  window.dockline = { getBackendState: vi.fn(), retryBackend: vi.fn(), openExternal }
  return {
    calls,
    bodies,
    openExternal,
    saveFails: (p: Problem) => (saveProblem = p),
    holdSave: () => (hold = true),
    releaseSave: () => release?.()
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

const field = () => screen.getByLabelText('API token') as HTMLInputElement

afterEach(() => vi.unstubAllGlobals())

describe('Settings: Hostinger section', () => {
  it('shows a hidden token field and a hint with a link opened outside the app when not connected', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend()
    renderSettings()

    expect(await screen.findByLabelText('API token')).toHaveAttribute('type', 'password')
    expect(field()).toHaveAttribute('autocomplete', 'off')
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()

    await user.click(screen.getByRole('link', { name: 'Open the API page in hPanel' }))
    expect(backend.openExternal).toHaveBeenCalledWith('https://hpanel.hostinger.com/api')
  })

  it('shows a busy state while saving, then "Connected" with an empty field and no token on screen', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend()
    backend.holdSave()
    renderSettings()

    await user.type(await screen.findByLabelText('API token'), TOKEN)
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('button', { name: 'Checking token…' })).toBeDisabled()
    expect(backend.bodies).toEqual([JSON.stringify({ token: TOKEN })])

    backend.releaseSave()
    expect(await screen.findByText('Connected')).toBeInTheDocument()
    expect(screen.queryByLabelText('API token')).not.toBeInTheDocument()
    expect(document.body.innerHTML).not.toContain(TOKEN)
  })

  it.each([
    [400, 'The token is empty or too long.'],
    [422, 'Hostinger did not accept this token. Nothing was saved.'],
    [429, 'Hostinger is rate limiting requests. Try again in a minute.'],
    [502, 'Hostinger could not be reached. Nothing was saved.']
  ])('shows the backend message for a %i answer and clears the field', async (status, detail) => {
    const user = userEvent.setup()
    const backend = fakeBackend()
    backend.saveFails({ status, title: 'Problem', detail })
    renderSettings()

    await user.type(await screen.findByLabelText('API token'), TOKEN)
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(detail)
    expect(field().value).toBe('')
    expect(screen.queryByText('Connected')).not.toBeInTheDocument()
  })

  it('shows the backend message for an ERROR status and keeps the token field available', async () => {
    fakeBackend({ state: 'ERROR', message: 'Hostinger no longer accepts the saved token. Replace the token in Settings.' })
    renderSettings()

    expect(await screen.findByText('Connection problem')).toBeInTheDocument()
    expect(screen.getByText(/no longer accepts the saved token/)).toBeInTheDocument()
    expect(screen.getByLabelText('API token')).toBeInTheDocument()
  })

  it('shows the backend message for a LIMITED status', async () => {
    fakeBackend({ state: 'LIMITED', message: 'Hostinger is rate limiting requests.' })
    renderSettings()

    expect(await screen.findByText('Connected, with limits')).toBeInTheDocument()
    expect(screen.getByText('Hostinger is rate limiting requests.')).toBeInTheDocument()
  })

  it('rechecks the token when Settings opens', async () => {
    const backend = fakeBackend({ state: 'CONNECTED' })
    renderSettings()
    await waitFor(() => expect(backend.calls).toContain('POST /api/hostinger/recheck'))
  })

  it('asks before disconnecting, and can be cancelled', async () => {
    const user = userEvent.setup()
    const backend = fakeBackend({ state: 'CONNECTED' })
    renderSettings()

    await user.click(await screen.findByRole('button', { name: 'Disconnect' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Keep connected' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(backend.calls).not.toContain('DELETE /api/hostinger/token')

    await user.click(screen.getByRole('button', { name: 'Disconnect' }))
    await user.click(screen.getByRole('button', { name: 'Yes, disconnect' }))

    expect(await screen.findByLabelText('API token')).toBeInTheDocument()
    expect(backend.calls).toContain('DELETE /api/hostinger/token')
  })
})
