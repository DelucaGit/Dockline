import type { WireMock } from './wiremock'

/**
 * A fake Hostinger public API for end-to-end tests, on top of a running WireMock:
 * GET /api/billing/v1/subscriptions, the call the backend uses to check a token. Errors look like
 * Hostinger's documented ones: `{ message, correlation_id }`.
 * The backend tests use the same fakes (backend/src/test/.../hostinger/HostingerStubs.java).
 *
 * A later stub for the same endpoint wins, so a test can change Hostinger's answer between two calls.
 *
 * Usage:
 *   const { window } = await launchApp(hostingerEnv(wiremock))
 *   await stubSubscriptionsOk(wiremock)
 *   ... later: await stubUnauthorized(wiremock)  // the token was revoked
 */

export const SUBSCRIPTIONS_PATH = '/api/billing/v1/subscriptions'

/** Fake values; none of them is a real credential. */
export const FAKE_HOSTINGER = {
  token: 'hst_FakeE2eTokenNOTreal0123456789abcdef',
  otherToken: 'hst_OtherFakeE2eTokenNOTreal987654321'
} as const

/** Environment for launchApp: point the Hostinger API at WireMock. */
export function hostingerEnv(wiremock: WireMock): Record<string, string> {
  return { DOCKLINE_HOSTINGER_API_URL: wiremock.url }
}

/** The token works; the account has one subscription. */
export async function stubSubscriptionsOk(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: SUBSCRIPTIONS_PATH },
    response: {
      status: 200,
      jsonBody: [
        {
          id: 'Azz353Uhl1xC54pR0',
          name: 'Premium Web Hosting',
          status: 'active',
          billing_period: 12,
          billing_period_unit: 'month',
          currency_code: 'USD',
          total_price: 1188,
          renewal_price: 1428,
          is_auto_renewed: true,
          created_at: '2025-02-27T11:54:22Z',
          expires_at: '2026-02-27T11:54:22Z'
        }
      ]
    }
  })
}

/** The token is missing, expired or revoked: 401, as Hostinger documents. */
export async function stubUnauthorized(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: SUBSCRIPTIONS_PATH },
    response: {
      status: 401,
      jsonBody: { message: 'Unauthenticated.', correlation_id: 'c0ffee00-0000-4000-8000-000000000001' }
    }
  })
}

/** Hostinger's rate limit (90 requests a minute) is used up: 429 with Retry-After. */
export async function stubRateLimited(wiremock: WireMock, retryAfterSeconds = 30): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: SUBSCRIPTIONS_PATH },
    response: {
      status: 429,
      headers: { 'Retry-After': String(retryAfterSeconds) },
      jsonBody: { message: 'Too Many Attempts.', correlation_id: 'c0ffee00-0000-4000-8000-000000000002' }
    }
  })
}

export async function stubServerError(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: SUBSCRIPTIONS_PATH },
    response: {
      status: 500,
      jsonBody: { message: 'Server Error', correlation_id: 'c0ffee00-0000-4000-8000-000000000003' }
    }
  })
}

/** No answer at all: the connection is reset. */
export async function stubConnectionReset(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: 'GET', urlPath: SUBSCRIPTIONS_PATH },
    response: { fault: 'CONNECTION_RESET_BY_PEER' }
  })
}
