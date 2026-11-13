import type { WireMock } from './wiremock'

/**
 * A fake GitHub for end-to-end tests, on top of a running WireMock: the device-flow endpoints
 * (github.com/login/...) and GET /user (api.github.com). The answers follow GitHub's documentation;
 * device-flow errors come back as HTTP 200 with an `error` field, as GitHub does.
 * The backend tests use the same fakes (backend/src/test/.../github/GithubStubs.java).
 *
 * A later stub for the same endpoint wins, so a test can change GitHub's answer between two polls.
 *
 * Usage:
 *   const { window } = await launchApp(githubEnv(wiremock))
 *   await stubDeviceCode(wiremock)
 *   await stubTokenPending(wiremock)
 *   ... later: await stubTokenSuccess(wiremock); await stubUser(wiremock)
 *
 * Note: the backend only asks GitHub once the polling interval has passed (real seconds in E2E),
 * so use a short `interval` (1) in tests.
 */

export const DEVICE_CODE_PATH = '/login/device/code'
export const TOKEN_PATH = '/login/oauth/access_token'
export const USER_PATH = '/user'

/** Fake values; none of them is a real credential. */
export const FAKE_GITHUB = {
  clientId: 'Ov23liFakeE2eClientId',
  deviceCode: '3584d83530557fdd1f46af8289938c8ef79f9dc5',
  userCode: 'WDJB-MJHT',
  verificationUri: 'https://github.com/login/device',
  accessToken: 'gho_FakeE2eTokenNOTreal0123456789abcdef',
  login: 'octo-test'
} as const

/** Environment for launchApp: point GitHub (API and device flow) at WireMock and set a fake client ID. */
export function githubEnv(wiremock: WireMock): Record<string, string> {
  return {
    DOCKLINE_GITHUB_API_URL: wiremock.url,
    DOCKLINE_GITHUB_OAUTH_URL: wiremock.url,
    DOCKLINE_GITHUB_CLIENT_ID: FAKE_GITHUB.clientId
  }
}

export async function stubDeviceCode(
  wiremock: WireMock,
  options: { userCode?: string; expiresIn?: number; interval?: number } = {}
): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'POST', urlPath: DEVICE_CODE_PATH },
    response: {
      status: 200,
      jsonBody: {
        device_code: FAKE_GITHUB.deviceCode,
        user_code: options.userCode ?? FAKE_GITHUB.userCode,
        verification_uri: FAKE_GITHUB.verificationUri,
        expires_in: options.expiresIn ?? 900,
        interval: options.interval ?? 1
      }
    }
  })
}

/** Any device-flow error answer from the token endpoint, e.g. 'device_flow_disabled'. */
export async function stubTokenError(wiremock: WireMock, error: string, extra: Record<string, unknown> = {}): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'POST', urlPath: TOKEN_PATH },
    response: { status: 200, jsonBody: { error, error_description: `fake ${error}`, ...extra } }
  })
}

/** The user has not approved yet. */
export const stubTokenPending = (wiremock: WireMock): Promise<void> => stubTokenError(wiremock, 'authorization_pending')

/** Polled too fast; GitHub sends the new interval to use. */
export const stubTokenSlowDown = (wiremock: WireMock, newInterval: number): Promise<void> =>
  stubTokenError(wiremock, 'slow_down', { interval: newInterval })

/** The user clicked Cancel on GitHub. */
export const stubTokenDenied = (wiremock: WireMock): Promise<void> => stubTokenError(wiremock, 'access_denied')

/** The code expired before approval. */
export const stubTokenExpired = (wiremock: WireMock): Promise<void> => stubTokenError(wiremock, 'expired_token')

/** The user approved: GitHub hands out the (fake) token. */
export async function stubTokenSuccess(wiremock: WireMock, accessToken: string = FAKE_GITHUB.accessToken): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'POST', urlPath: TOKEN_PATH },
    response: { status: 200, jsonBody: { access_token: accessToken, token_type: 'bearer', scope: 'read:user,repo' } }
  })
}

/** The token works: GET /user answers with this login. */
export async function stubUser(wiremock: WireMock, login: string = FAKE_GITHUB.login): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: USER_PATH },
    response: { status: 200, jsonBody: { login, id: 1, type: 'User' } }
  })
}

/** The token was revoked or expired: GET /user answers 401. */
export async function stubUserUnauthorized(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: USER_PATH },
    response: {
      status: 401,
      jsonBody: { message: 'Bad credentials', documentation_url: 'https://docs.github.com/rest', status: '401' }
    }
  })
}

/** The rate limit is used up: GET /user answers 403 with x-ratelimit-remaining: 0. */
export async function stubUserRateLimited(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: USER_PATH },
    response: {
      status: 403,
      headers: { 'x-ratelimit-limit': '5000', 'x-ratelimit-remaining': '0' },
      jsonBody: { message: 'API rate limit exceeded', documentation_url: 'https://docs.github.com/rest' }
    }
  })
}
