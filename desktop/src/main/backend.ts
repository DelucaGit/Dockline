import { type ChildProcess, spawn, spawnSync } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import { createServer } from 'node:net'
import { join } from 'node:path'
import type { BackendState } from '../shared/backend'

const HEALTH_TIMEOUT_MS = 60_000
const POLL_INTERVAL_MS = 300
const LOG_TAIL_LINES = 15

/**
 * Which Java to run the backend with. Kept in one place: task #52 will replace
 * this with the Java runtime bundled in the installer.
 */
export function resolveJava(env: NodeJS.ProcessEnv = process.env): string {
  if (env['DOCKLINE_JAVA']) return env['DOCKLINE_JAVA']
  if (env['JAVA_HOME']) {
    return join(env['JAVA_HOME'], 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  }
  return 'java'
}

/** Where the backend jar is. DOCKLINE_BACKEND_JAR overrides; otherwise the Maven build output. */
export function resolveJar(appPath: string, env: NodeJS.ProcessEnv = process.env): string | null {
  if (env['DOCKLINE_BACKEND_JAR']) return env['DOCKLINE_BACKEND_JAR']
  const targetDir = join(appPath, '..', 'backend', 'target')
  if (!existsSync(targetDir)) return null
  const jar = readdirSync(targetDir).find(
    (f) => f.startsWith('dockline-backend-') && f.endsWith('.jar') && !f.endsWith('.original')
  )
  return jar ? join(targetDir, jar) : null
}

export function findFreePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address() as { port: number }
      server.close(() => resolve(port))
    })
  })
}

/** Kill a process and all its children (on Windows `taskkill /T /F`). Synchronous so it finishes during quit. */
export function killProcessTree(pid: number): void {
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true })
  } else {
    try {
      process.kill(pid, 'SIGKILL')
    } catch {
      // already gone
    }
  }
}

async function isHealthy(baseUrl: string): Promise<boolean> {
  try {
    const res = await fetch(`${baseUrl}/actuator/health`, { signal: AbortSignal.timeout(2000) })
    if (!res.ok) return false
    return ((await res.json()) as { status?: string }).status === 'UP'
  } catch {
    return false
  }
}

/** Owns the backend child process: start, wait until healthy, stop. */
export class BackendProcess {
  private child: ChildProcess | null = null
  private state: BackendState = { status: 'starting' }
  private generation = 0

  constructor(private readonly appPath: string) {}

  getState(): BackendState {
    return this.state
  }

  /** Start (or restart) the backend. Resolves once it is ready or has failed. */
  async start(): Promise<BackendState> {
    this.stop()
    const run = ++this.generation
    this.state = { status: 'starting' }
    const set = (s: BackendState): BackendState =>
      run === this.generation ? (this.state = s) : this.state

    const jar = resolveJar(this.appPath)
    if (!jar || !existsSync(jar)) {
      return set({
        status: 'error',
        message: `The backend jar was not found${jar ? ` at ${jar}` : ''}. Build it with "./mvnw verify" in backend/ or set DOCKLINE_BACKEND_JAR.`
      })
    }

    let port: number
    try {
      port = await findFreePort()
    } catch (e) {
      return set({ status: 'error', message: `Could not find a free port: ${String(e)}` })
    }
    const baseUrl = `http://127.0.0.1:${port}`
    const java = resolveJava()

    const tail: string[] = []
    const collect = (chunk: Buffer): void => {
      tail.push(...chunk.toString().split(/\r?\n/).filter(Boolean))
      if (tail.length > LOG_TAIL_LINES) tail.splice(0, tail.length - LOG_TAIL_LINES)
    }

    const child = spawn(java, ['-jar', jar, `--server.port=${port}`, '--server.address=127.0.0.1'], {
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true
    })
    this.child = child
    child.stdout?.on('data', collect)
    child.stderr?.on('data', collect)

    let failure: string | null = null
    child.once('error', (err: NodeJS.ErrnoException) => {
      failure =
        err.code === 'ENOENT'
          ? `Java was not found ("${java}"). Install Java 25 or set DOCKLINE_JAVA / JAVA_HOME.`
          : `The backend could not be started: ${err.message}`
    })
    child.once('exit', (code) => {
      failure ??= `The backend stopped unexpectedly (exit code ${code}).\n${tail.join('\n')}`
      if (this.child === child) this.child = null
      // Died after it was ready: tell the renderer.
      if (this.state.status === 'ready' && run === this.generation) {
        set({ status: 'error', message: failure })
      }
    })

    const deadline = Date.now() + HEALTH_TIMEOUT_MS
    while (Date.now() < deadline) {
      if (run !== this.generation) return this.state
      if (failure) return set({ status: 'error', message: failure })
      if (await isHealthy(baseUrl)) return set({ status: 'ready', baseUrl })
      await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS))
    }
    this.stop()
    return set({
      status: 'error',
      message: `The backend did not become ready within ${HEALTH_TIMEOUT_MS / 1000} seconds.\n${tail.join('\n')}`
    })
  }

  /** Stop the backend and everything it started. */
  stop(): void {
    const child = this.child
    this.child = null
    if (child?.pid && child.exitCode === null) killProcessTree(child.pid)
  }
}
