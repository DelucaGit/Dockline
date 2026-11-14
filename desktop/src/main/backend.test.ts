// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { join } from 'node:path'
import { backendCommand, resolveJar, resolveJava } from './backend'

describe('backendCommand', () => {
  it('starts the jar on the given localhost port', () => {
    const cmd = backendCommand('/jdk/bin/java', '/app/backend.jar', 4321, {})

    expect(cmd.command).toBe('/jdk/bin/java')
    expect(cmd.args).toEqual([
      '-jar',
      '/app/backend.jar',
      '--server.port=4321',
      '--server.address=127.0.0.1'
    ])
  })

  it('passes the environment on, so tests can point the backend at fake external APIs', () => {
    const cmd = backendCommand('java', 'backend.jar', 4321, {
      PATH: '/usr/bin',
      DOCKLINE_GITHUB_API_URL: 'http://127.0.0.1:9001',
      DOCKLINE_HOSTINGER_API_URL: 'http://127.0.0.1:9002'
    })

    expect(cmd.env).toMatchObject({
      PATH: '/usr/bin',
      DOCKLINE_GITHUB_API_URL: 'http://127.0.0.1:9001',
      DOCKLINE_HOSTINGER_API_URL: 'http://127.0.0.1:9002'
    })
  })
})

describe('packaged app (installed with the installer)', () => {
  const packaged = { resourcesPath: join('C:', 'Program Files', 'Dockline', 'resources') }
  const javaOnMachine = { JAVA_HOME: '/jdk', DOCKLINE_JAVA: '/other/java', PATH: '/jdk/bin' }

  it('runs the bundled Java runtime, never one from JAVA_HOME, DOCKLINE_JAVA or PATH', () => {
    const java = resolveJava(javaOnMachine, packaged)

    const exe = process.platform === 'win32' ? 'java.exe' : 'java'
    expect(java).toBe(join(packaged.resourcesPath, 'runtime', 'bin', exe))
  })

  it('runs the bundled backend jar, ignoring DOCKLINE_BACKEND_JAR', () => {
    const jar = resolveJar('/app', { DOCKLINE_BACKEND_JAR: '/dev/backend.jar' }, packaged)

    expect(jar).toBe(join(packaged.resourcesPath, 'backend', 'dockline-backend.jar'))
  })

  it('keeps the dev behaviour when not packaged', () => {
    expect(resolveJava({ DOCKLINE_JAVA: '/other/java' }, null)).toBe('/other/java')
    expect(resolveJar('/app', { DOCKLINE_BACKEND_JAR: '/dev/backend.jar' }, null)).toBe(
      '/dev/backend.jar'
    )
  })
})
