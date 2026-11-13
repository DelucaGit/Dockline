import { type ChildProcess, spawn } from 'node:child_process'
import { mkdtempSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { findFreePort, killProcessTree, resolveJava } from '../../src/main/backend'

/** A WireMock stub mapping in its JSON form, see https://wiremock.org/docs/stubbing/ */
export interface StubMapping {
  request: { method?: string; url?: string; urlPath?: string; urlPathPattern?: string; [k: string]: unknown }
  response: {
    status?: number
    jsonBody?: unknown
    body?: string
    headers?: Record<string, string>
    [k: string]: unknown
  }
  /** 1 is the highest; a stub with a higher priority is tried first. */
  priority?: number
  /** WireMock scenario: the stub only matches in `requiredScenarioState` and then moves to `newScenarioState`. */
  scenarioName?: string
  requiredScenarioState?: string
  newScenarioState?: string
}

/** A running fake HTTP server (WireMock standalone), e.g. a fake GitHub or Hostinger API. */
export interface WireMock {
  /** Base URL without trailing slash, e.g. http://127.0.0.1:51234 */
  readonly url: string
  /** Add a stub: when a request matches, answer with the given response. */
  stubFor(mapping: StubMapping): Promise<void>
  /** Every request WireMock received so far (to assert what the backend called). */
  requests(): Promise<Array<{ method: string; url: string; headers: Record<string, string> }>>
  /** Remove all stubs and recorded requests. */
  reset(): Promise<void>
  /** Stop the server (safe to call more than once). */
  stop(): Promise<void>
}

const STARTUP_TIMEOUT_MS = 30_000

/** The standalone jar shipped by the `wiremock` npm package (no Docker needed, only Java). */
function wiremockJar(): string {
  const dir = join(import.meta.dirname, '..', '..', 'node_modules', 'wiremock', 'build')
  const jar = readdirSync(dir).find((f) => /^wiremock-standalone-.*\.jar$/.test(f))
  if (!jar) throw new Error(`No WireMock standalone jar found in ${dir}. Run npm install.`)
  return join(dir, jar)
}

/** Start WireMock on a free localhost port, with the same Java as the backend (DOCKLINE_JAVA / JAVA_HOME). */
export async function startWireMock(): Promise<WireMock> {
  const port = await findFreePort()
  const url = `http://127.0.0.1:${port}`
  const rootDir = mkdtempSync(join(tmpdir(), 'dockline-wiremock-'))
  const output: string[] = []

  const child: ChildProcess = spawn(
    resolveJava(),
    [
      '-jar',
      wiremockJar(),
      '--port',
      String(port),
      '--bind-address',
      '127.0.0.1',
      '--root-dir',
      rootDir,
      '--disable-banner'
    ],
    { cwd: rootDir, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true }
  )
  let exited = false
  child.stdout?.on('data', (c: Buffer) => output.push(c.toString()))
  child.stderr?.on('data', (c: Buffer) => output.push(c.toString()))
  child.once('exit', () => (exited = true))
  child.once('error', (e) => {
    exited = true
    output.push(String(e))
  })

  const stop = async (): Promise<void> => {
    if (!exited && child.pid) {
      const gone = new Promise<void>((resolve) => child.once('exit', () => resolve()))
      killProcessTree(child.pid)
      await gone
    }
    rmSync(rootDir, { recursive: true, force: true })
  }

  const admin = async (path: string, init?: RequestInit): Promise<Response> => {
    const res = await fetch(`${url}/__admin${path}`, init)
    if (!res.ok) {
      throw new Error(`WireMock ${init?.method ?? 'GET'} ${path} failed: ${res.status} ${await res.text()}`)
    }
    return res
  }

  const deadline = Date.now() + STARTUP_TIMEOUT_MS
  for (;;) {
    if (exited) {
      await stop()
      throw new Error(`WireMock exited during start-up:\n${output.join('')}`)
    }
    try {
      if ((await fetch(`${url}/__admin/health`)).ok) break
    } catch {
      // not listening yet
    }
    if (Date.now() > deadline) {
      await stop()
      throw new Error(`WireMock did not start within ${STARTUP_TIMEOUT_MS / 1000} s:\n${output.join('')}`)
    }
    await new Promise((r) => setTimeout(r, 200))
  }

  return {
    url,
    async stubFor(mapping) {
      await admin('/mappings', { method: 'POST', body: JSON.stringify(mapping) })
    },
    async requests() {
      const body = (await (await admin('/requests')).json()) as {
        requests: Array<{ request: { method: string; url: string; headers: Record<string, string> } }>
      }
      return body.requests.map((r) => r.request)
    },
    async reset() {
      await admin('/reset', { method: 'POST' })
    },
    stop
  }
}
