import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App } from './App'
import { BackendGate } from './components/BackendGate'
import { BackendStatus } from './components/BackendStatus'
import './index.css'

const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <HashRouter>
        <BackendGate>
          {(baseUrl) => <App footer={<BackendStatus baseUrl={baseUrl} />} />}
        </BackendGate>
      </HashRouter>
    </QueryClientProvider>
  </StrictMode>
)
