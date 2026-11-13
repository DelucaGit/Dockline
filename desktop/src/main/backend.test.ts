// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { backendCommand } from './backend'

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
