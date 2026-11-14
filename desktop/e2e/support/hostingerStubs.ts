import type { WireMock } from "./wiremock";

/**
 * A fake Hostinger public API for end-to-end tests, on top of a running WireMock:
 * GET /api/billing/v1/subscriptions, the call the backend uses to check a token, and
 * GET /api/hosting/v1/websites, the paged website list (`stubSites`), and GET /api/domains/v1/portfolio,
 * the domain list (`stubDomains`). Errors look like
 * Hostinger's documented ones: `{ message, correlation_id }`.
 * A website's Git connection (shapes from Hostinger's OpenAPI document) has a fake for each case:
 * `stubGitAutoDeployment` (connected through Hostinger's GitHub app), `stubGitRepositories` (a repository
 * cloned in hPanel's Git section) and `stubGitNotConnected`. A Node.js website's build history (its deploys)
 * is `stubBuilds`, with `stubBuildsServerError` and `stubBuildsRateLimited` for the failures.
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
  const username =
    site.username === undefined ? DEFAULT_USERNAME : site.username;
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

/**
 * The website list answers 429 with Retry-After ONCE, then the other website stubs (e.g. `stubSites`) answer
 * again: Hostinger saying "slow down" and then recovering. A WireMock scenario; `wiremock.reset()` starts it over.
 * With a short wait (at most 10 s) the backend waits inside the request and retries; with a longer one it
 * answers 429 HOSTINGER_RATE_LIMITED at once with `retryAfterSeconds`, and GET /api/status counts down.
 */
export async function stubSitesRateLimitedOnce(
  wiremock: WireMock,
  retryAfterSeconds = 2,
): Promise<void> {
  await wiremock.stubFor({
    priority: 1,
    scenarioName: "websites-rate-limited-once",
    requiredScenarioState: "Started",
    newScenarioState: "recovered",
    request: { method: "GET", urlPath: WEBSITES_PATH },
    response: {
      status: 429,
      headers: { "Retry-After": String(retryAfterSeconds) },
      jsonBody: {
        message: "Too Many Attempts.",
        correlation_id: "c0ffee00-0000-4000-8000-000000000014",
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

export const DOMAINS_PATH = "/api/domains/v1/portfolio";

/** One domain as the tests describe it; `stubDomains` turns it into Hostinger's JSON. */
export interface FakeDomain {
  /** null for a free domain that is not claimed yet. */
  domain: string | null;
  /** Hostinger's format, e.g. 2026-02-27T11:54:22Z; null for no expiry date. Default: 2027-01-01T00:00:00Z. */
  expiresAt?: string | null;
  status?:
    | "active"
    | "pending_setup"
    | "expired"
    | "requested"
    | "pending_verification"
    | "deleted"
    | "suspended"
    | "failed";
  type?: "domain" | "free_domain" | "domain_transfer" | "free_domain_transfer";
  createdAt?: string;
}

/** The domain portfolio: a plain JSON array of domains, no paging. */
export async function stubDomains(
  wiremock: WireMock,
  domains: FakeDomain[],
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: DOMAINS_PATH },
    response: {
      status: 200,
      jsonBody: domains.map((d, i) => ({
        id: 1000 + i,
        domain: d.domain,
        type: d.type ?? "domain",
        status: d.status ?? "active",
        created_at: d.createdAt ?? "2025-02-27T11:54:22Z",
        expires_at:
          d.expiresAt === undefined ? "2027-01-01T00:00:00Z" : d.expiresAt,
      })),
    },
  });
}

/** The domain portfolio answers 401: the token is missing, expired or revoked. */
export async function stubDomainsUnauthorized(
  wiremock: WireMock,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: DOMAINS_PATH },
    response: {
      status: 401,
      jsonBody: {
        message: "Unauthenticated.",
        correlation_id: "c0ffee00-0000-4000-8000-000000000021",
      },
    },
  });
}

/** The domain portfolio answers 429 with Retry-After. */
export async function stubDomainsRateLimited(
  wiremock: WireMock,
  retryAfterSeconds = 30,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: DOMAINS_PATH },
    response: {
      status: 429,
      headers: { "Retry-After": String(retryAfterSeconds) },
      jsonBody: {
        message: "Too Many Attempts.",
        correlation_id: "c0ffee00-0000-4000-8000-000000000022",
      },
    },
  });
}

export async function stubDomainsServerError(
  wiremock: WireMock,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: DOMAINS_PATH },
    response: {
      status: 500,
      jsonBody: {
        message: "Server Error",
        correlation_id: "c0ffee00-0000-4000-8000-000000000023",
      },
    },
  });
}

export const GIT_INSTALLATIONS_PATH = "/api/hosting/v1/git/installations";
/** The installation every `stubGitAutoDeployment` fake goes through. */
export const GIT_INSTALLATION_UUID = "018f5e2a-1234-7890-abcd-1234567890ab";

/** The hosting account `stubSites` gives a site by default. */
export const DEFAULT_USERNAME = "u123456789";

export function gitRepositoriesPath(domain: string, username = DEFAULT_USERNAME): string {
  return `/api/hosting/v1/accounts/${username}/websites/${domain}/git/repositories`;
}

export function gitAutoDeploymentPath(domain: string, username = DEFAULT_USERNAME): string {
  return `/api/hosting/v1/accounts/${username}/websites/${domain}/git/auto-deployments/settings`;
}

/**
 * The website's hPanel Git section lists these clone addresses (the first one in the document root, the others
 * in dir-1, dir-2 ...), e.g. "git@github.com:octocat/my-site.git" or "https://***@github.com/octocat/my-site.git".
 * An empty list means nothing is cloned there.
 */
export async function stubGitRepositories(
  wiremock: WireMock,
  domain: string,
  repositoryUrls: string[],
  username = DEFAULT_USERNAME,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: gitRepositoriesPath(domain, username) },
    response: {
      status: 200,
      jsonBody: repositoryUrls.map((url, i) => ({
        repository_url: url,
        branch: "main",
        directory: i === 0 ? "" : `dir-${i}`,
        webhook: {
          url: `https://webhooks.hostinger.com/deploy/6f1ed002ab5595859014ebf0951522d${i}`,
          provider: null,
          setup_url: null,
          tutorial_url: null,
        },
      })),
    },
  });
}

/**
 * The website auto-deploys `owner/repository` (branch main, document root) through the installation
 * GIT_INSTALLATION_UUID; `stubGitInstallations` says which provider that is.
 */
export async function stubGitAutoDeployment(
  wiremock: WireMock,
  domain: string,
  repo: { owner: string; repository: string },
  username = DEFAULT_USERNAME,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: gitAutoDeploymentPath(domain, username) },
    response: {
      status: 200,
      jsonBody: {
        installation_uuid: GIT_INSTALLATION_UUID,
        is_enabled: true,
        owner: repo.owner,
        repository: repo.repository,
        branch: "main",
        directory: "",
      },
    },
  });
}

/** The website has no auto-deployment: every field is null, as Hostinger documents. */
export async function stubGitNoAutoDeployment(
  wiremock: WireMock,
  domain: string,
  username = DEFAULT_USERNAME,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: gitAutoDeploymentPath(domain, username) },
    response: {
      status: 200,
      jsonBody: {
        installation_uuid: null,
        is_enabled: null,
        owner: null,
        repository: null,
        branch: null,
        directory: null,
      },
    },
  });
}

/** A website that is not Git-connected at all: no auto-deployment and an empty Git section. */
export async function stubGitNotConnected(
  wiremock: WireMock,
  domain: string,
  username = DEFAULT_USERNAME,
): Promise<void> {
  await stubGitNoAutoDeployment(wiremock, domain, username);
  await stubGitRepositories(wiremock, domain, [], username);
}

/** The customer's one Git installation (GIT_INSTALLATION_UUID) and its provider. */
export async function stubGitInstallations(
  wiremock: WireMock,
  provider: "github" | "gitlab" | "bitbucket" = "github",
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: GIT_INSTALLATIONS_PATH },
    response: {
      status: 200,
      jsonBody: [
        {
          uuid: GIT_INSTALLATION_UUID,
          provider,
          account_login: "octocat",
          account_type: "User",
          account_avatar_url: null,
          status: "active",
          installed_at: "2026-01-15T10:30:00Z",
          created_at: "2026-01-15T10:00:00Z",
          has_oauth: true,
        },
      ],
    },
  });
}

/** Both Git endpoints of the website answer 500. */
export async function stubGitServerError(
  wiremock: WireMock,
  domain: string,
  username = DEFAULT_USERNAME,
): Promise<void> {
  for (const urlPath of [
    gitAutoDeploymentPath(domain, username),
    gitRepositoriesPath(domain, username),
  ]) {
    await wiremock.stubFor({
      request: { method: "GET", urlPath },
      response: {
        status: 500,
        jsonBody: {
          message: "Server Error",
          correlation_id: "c0ffee00-0000-4000-8000-000000000033",
        },
      },
    });
  }
}

export function nodejsBuildsPath(domain: string, username = DEFAULT_USERNAME): string {
  return `/api/hosting/v1/accounts/${username}/websites/${domain}/nodejs/builds`;
}

/** One build as the tests describe it; `stubBuilds` turns it into Hostinger's JSON. */
export interface FakeBuild {
  state: "completed" | "failed" | "pending" | "running";
  /** Full commit hash; the app shows its first 7 characters. */
  commit?: string;
  message?: string;
  createdAt?: string;
}

/**
 * The Node.js build history of a website (`stubSites` with `websiteType: "nodejs"`), all on page 1:
 * `{ data: [...], meta: { current_page, per_page, total } }`, as Hostinger answers it.
 */
export async function stubBuilds(
  wiremock: WireMock,
  domain: string,
  builds: FakeBuild[],
  username = DEFAULT_USERNAME,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: nodejsBuildsPath(domain, username) },
    response: {
      status: 200,
      jsonBody: {
        data: builds.map((b, i) => {
          const created = b.createdAt ?? `2026-05-0${(i % 9) + 1}T10:00:00Z`;
          return {
            uuid: `0190a1b2-0000-7000-8000-00000000000${i % 10}`,
            state: b.state,
            options: {
              source_type: "git",
              source_options: {
                owner: "octocat",
                repository: "my-site",
                branch: "main",
                commit: {
                  hash: b.commit ?? "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
                  message: b.message ?? "Initial commit",
                },
              },
            },
            created_at: created,
            updated_at: created,
          };
        }),
        meta: { current_page: 1, per_page: 100, total: builds.length },
      },
    },
  });
}

/** The builds endpoint of the website answers 500. */
export async function stubBuildsServerError(
  wiremock: WireMock,
  domain: string,
  username = DEFAULT_USERNAME,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: nodejsBuildsPath(domain, username) },
    response: {
      status: 500,
      jsonBody: {
        message: "Server Error",
        correlation_id: "c0ffee00-0000-4000-8000-000000000041",
      },
    },
  });
}

/** The builds endpoint of the website answers 429 with Retry-After. */
export async function stubBuildsRateLimited(
  wiremock: WireMock,
  domain: string,
  retryAfterSeconds = 30,
  username = DEFAULT_USERNAME,
): Promise<void> {
  await wiremock.stubFor({
    request: { method: "GET", urlPath: nodejsBuildsPath(domain, username) },
    response: {
      status: 429,
      headers: { "Retry-After": String(retryAfterSeconds) },
      jsonBody: {
        message: "Too Many Attempts.",
        correlation_id: "c0ffee00-0000-4000-8000-000000000042",
      },
    },
  });
}

/** How many requests WireMock received for the website's builds so far. */
export async function countBuildsRequests(
  wiremock: WireMock,
  domain: string,
  username = DEFAULT_USERNAME,
): Promise<number> {
  const path = nodejsBuildsPath(domain, username);
  return (await wiremock.requests()).filter((r) => r.url.split("?")[0] === path).length;
}
