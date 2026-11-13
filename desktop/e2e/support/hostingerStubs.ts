import type { WireMock } from "./wiremock";

/**
 * A fake Hostinger public API for end-to-end tests, on top of a running WireMock:
 * GET /api/billing/v1/subscriptions, the call the backend uses to check a token, and
 * GET /api/hosting/v1/websites, the paged website list (`stubSites`). Errors look like
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

export const SUBSCRIPTIONS_PATH = "/api/billing/v1/subscriptions";
export const WEBSITES_PATH = "/api/hosting/v1/websites";

/** Fake values; none of them is a real credential. */
export const FAKE_HOSTINGER = {
  token: "hst_FakeE2eTokenNOTreal0123456789abcdef",
  otherToken: "hst_OtherFakeE2eTokenNOTreal987654321",
} as const;

/** Environment for launchApp: point the Hostinger API at WireMock. */
export function hostingerEnv(wiremock: WireMock): Record<string, string> {
  return { DOCKLINE_HOSTINGER_API_URL: wiremock.url };
}

/** The token works; the account has one subscription. */
export async function stubSubscriptionsOk(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: SUBSCRIPTIONS_PATH },
    response: {
      status: 200,
      jsonBody: [
        {
          id: "Azz353Uhl1xC54pR0",
          name: "Premium Web Hosting",
          status: "active",
          billing_period: 12,
          billing_period_unit: "month",
          currency_code: "USD",
          total_price: 1188,
          renewal_price: 1428,
          is_auto_renewed: true,
          created_at: "2025-02-27T11:54:22Z",
          expires_at: "2026-02-27T11:54:22Z",
        },
      ],
    },
  });
}

/** The token is missing, expired or revoked: 401, as Hostinger documents. */
export async function stubUnauthorized(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: SUBSCRIPTIONS_PATH },
    response: {
      status: 401,
      jsonBody: {
        message: "Unauthenticated.",
        correlation_id: "c0ffee00-0000-4000-8000-000000000001",
      },
    },
  });
}

/** Hostinger's rate limit (90 requests a minute) is used up: 429 with Retry-After. */
export async function stubRateLimited(
  wiremock: WireMock,
  retryAfterSeconds = 30,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: SUBSCRIPTIONS_PATH },
    response: {
      status: 429,
      headers: { "Retry-After": String(retryAfterSeconds) },
      jsonBody: {
        message: "Too Many Attempts.",
        correlation_id: "c0ffee00-0000-4000-8000-000000000002",
      },
    },
  });
}

export async function stubServerError(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: SUBSCRIPTIONS_PATH },
    response: {
      status: 500,
      jsonBody: {
        message: "Server Error",
        correlation_id: "c0ffee00-0000-4000-8000-000000000003",
      },
    },
  });
}

/** No answer at all: the connection is reset. */
export async function stubConnectionReset(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: SUBSCRIPTIONS_PATH },
    response: { fault: "CONNECTION_RESET_BY_PEER" },
  });
}

/** One website as the tests describe it; `stubSites` turns it into Hostinger's JSON. */
export interface FakeSite {
  /** null for a website with no domain attached. */
  domain: string | null;
  username?: string | null;
  orderId?: number;
  enabled?: boolean;
  websiteType?: "wordpress" | "builder" | "horizons" | "nodejs" | "other";
  /** Hostinger's format, e.g. 2024-01-15T10:30:00+00:00. */
  createdAt?: string;
}

function websiteJson(site: FakeSite): Record<string, unknown> {
  const username = site.username === undefined ? "u123456789" : site.username;
  return {
    domain: site.domain,
    vhost_type: username === null ? null : "main",
    is_enabled: site.enabled ?? true,
    username,
    client_id: 67890,
    order_id: site.orderId ?? 12345,
    created_at: site.createdAt ?? "2024-01-15T10:30:00+00:00",
    root_directory:
      username === null || site.domain === null
        ? null
        : `/home/${username}/domains/${site.domain}/public_html`,
    parent_domain: null,
    website_type: site.websiteType ?? "wordpress",
    horizons_uuid: null,
  };
}

/**
 * The website list, split over `pages` pages (default 1) and answered per `?page=N` the way Hostinger does:
 * `{ data: [...], meta: { current_page, per_page, total } }`. `per_page` is the page size used here, so a
 * short (or empty) last page ends the list. Pages past the end are empty.
 */
export async function stubSites(
  wiremock: WireMock,
  sites: FakeSite[],
  options: { pages?: number } = {},
): Promise<void> {
  const pages = Math.max(1, options.pages ?? 1);
  const perPage = Math.max(1, Math.ceil(sites.length / pages));
  const meta = (page: number): Record<string, number> => ({
    current_page: page,
    per_page: perPage,
    total: sites.length,
  });
  await wiremock.stubFor({
    request: { method: "GET", urlPath: WEBSITES_PATH },
    response: { status: 200, jsonBody: { data: [], meta: meta(pages + 1) } },
  });
  for (let page = 1; page <= pages; page++) {
    await wiremock.stubFor({
      request: {
        method: "GET",
        urlPath: WEBSITES_PATH,
        queryParameters: { page: { equalTo: String(page) } },
      },
      response: {
        status: 200,
        jsonBody: {
          data: sites
            .slice((page - 1) * perPage, page * perPage)
            .map(websiteJson),
          meta: meta(page),
        },
      },
    });
  }
}

/** The website list answers 401: the token is missing, expired or revoked. */
export async function stubSitesUnauthorized(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: WEBSITES_PATH },
    response: {
      status: 401,
      jsonBody: {
        message: "Unauthenticated.",
        correlation_id: "c0ffee00-0000-4000-8000-000000000011",
      },
    },
  });
}

/** The website list answers 429 with Retry-After. */
export async function stubSitesRateLimited(
  wiremock: WireMock,
  retryAfterSeconds = 30,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: WEBSITES_PATH },
    response: {
      status: 429,
      headers: { "Retry-After": String(retryAfterSeconds) },
      jsonBody: {
        message: "Too Many Attempts.",
        correlation_id: "c0ffee00-0000-4000-8000-000000000012",
      },
    },
  });
}

export async function stubSitesServerError(wiremock: WireMock): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: WEBSITES_PATH },
    response: {
      status: 500,
      jsonBody: {
        message: "Server Error",
        correlation_id: "c0ffee00-0000-4000-8000-000000000013",
      },
    },
  });
}
