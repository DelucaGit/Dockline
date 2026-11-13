import type { WireMock } from './wiremock'

/**
 * A fake GitHub for end-to-end tests, on top of a running WireMock: the device-flow endpoints
 * (github.com/login/...) and GET /user (api.github.com). The answers follow GitHub's documentation;
 * device-flow errors come back as HTTP 200 with an `error` field, as GitHub does.
 * The backend tests use the same fakes (backend/src/test/.../github/GithubStubs.java).
 *
 * For GET /user/repos, `stubRepos` serves several pages linked with absolute `Link` headers, as GitHub does.
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
export const REPOS_PATH = '/user/repos'

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

/** One repository as the tests describe it; `stubRepos` turns it into GitHub's JSON. */
export interface FakeRepo {
  owner?: string
  name: string
  private?: boolean
  /** ISO-8601, or null for a repository nobody has pushed to. */
  pushedAt?: string | null
}

/** GitHub's JSON for one repository (the fields Dockline reads, plus a few). */
export function githubRepoJson(repo: FakeRepo): Record<string, unknown> {
  const owner = repo.owner ?? FAKE_GITHUB.login
  return {
    id: Math.abs([...`${owner}/${repo.name}`].reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7)),
    name: repo.name,
    full_name: `${owner}/${repo.name}`,
    private: repo.private ?? false,
    html_url: `https://github.com/${owner}/${repo.name}`,
    description: null,
    fork: false,
    archived: false,
    visibility: repo.private ? 'private' : 'public',
    pushed_at: repo.pushedAt === undefined ? '2026-01-01T00:00:00Z' : repo.pushedAt,
    owner: { login: owner }
  }
}

/** The address GitHub puts into a Link header for that page of GET /user/repos. */
export function reposPageUrl(wiremock: WireMock, page: number): string {
  return (
    `${wiremock.url}${REPOS_PATH}?visibility=all&affiliation=owner%2Ccollaborator%2Corganization_member` +
    `&sort=pushed&direction=desc&per_page=100&page=${page}`
  )
}

/**
 * GET /user/repos answers with these repositories, split into `pages` pages (default 1). Every page but the
 * last has a Link header with absolute `next` and `last` addresses on WireMock, like GitHub's.
 */
export async function stubRepos(wiremock: WireMock, repos: FakeRepo[], options: { pages?: number } = {}): Promise<void> {
  const pages = Math.max(1, options.pages ?? 1)
  const perPage = Math.ceil(repos.length / pages) || 1
  for (let page = 1; page <= pages; page++) {
    const items = repos.slice((page - 1) * perPage, page * perPage).map(githubRepoJson)
    const headers: Record<string, string> = {}
    if (page < pages) {
      headers.Link = `<${reposPageUrl(wiremock, page + 1)}>; rel="next", <${reposPageUrl(wiremock, pages)}>; rel="last"`
    }
    await wiremock.stubFor({
      request: {
        method: 'GET',
        urlPath: REPOS_PATH,
        queryParameters: { page: page === 1 ? { absent: true } : { equalTo: String(page) } }
      },
      response: { status: 200, jsonBody: items, headers }
    })
  }
}

/** The token was revoked or expired: GET /user/repos answers 401. */
export async function stubReposUnauthorized(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: REPOS_PATH },
    response: {
      status: 401,
      jsonBody: { message: 'Bad credentials', documentation_url: 'https://docs.github.com/rest', status: '401' }
    }
  })
}

/** GitHub fails: GET /user/repos answers 503. */
export async function stubReposServerError(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: REPOS_PATH },
    response: { status: 503, jsonBody: { message: 'Service Unavailable' } }
  })
}

/** The rate limit is used up: GET /user/repos answers 429 with Retry-After. */
export async function stubReposRateLimited(wiremock: WireMock, retryAfterSeconds = 60): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: REPOS_PATH },
    response: {
      status: 429,
      headers: { 'Retry-After': String(retryAfterSeconds), 'x-ratelimit-remaining': '0' },
      jsonBody: { message: 'API rate limit exceeded', documentation_url: 'https://docs.github.com/rest' }
    }
  })
}
