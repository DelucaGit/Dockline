import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { App } from './App'

function renderApp() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <App />
    </MemoryRouter>
  )
}

describe('sidebar navigation', () => {
  it('shows all six menu items and starts on Overview', () => {
    renderApp()
    const nav = screen.getByRole('navigation', { name: 'Main menu' })
    for (const name of ['Overview', 'Sites', 'Repos', 'Domains', 'Log', 'Settings']) {
      expect(nav).toHaveTextContent(name)
    }
    expect(screen.getByRole('heading', { name: 'Overview' })).toBeInTheDocument()
  })

  it('shows the page of the clicked menu item', async () => {
    const user = userEvent.setup()
    renderApp()
    for (const name of ['Sites', 'Repos', 'Domains', 'Log', 'Settings', 'Overview']) {
      await user.click(screen.getByRole('link', { name }))
      expect(screen.getByRole('heading', { name })).toBeInTheDocument()
    }
  })
})
