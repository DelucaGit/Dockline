import type { Page } from "@playwright/test";
import { expect, test } from "./support/fixtures";
import {
  FAKE_HOSTINGER,
  hostingerEnv,
  stubDomains,
  stubDomainsRateLimited,
  stubDomainsServerError,
  stubDomainsUnauthorized,
  stubSubscriptionsOk,
} from "./support/hostingerStubs";

interface Reply {
  status: number;
  contentType: string | null;
  retryAfter: string | null;
  body: string;
}

/** Call the backend from the renderer, as the UI will. */
async function api(
  window: Page,
  method: string,
  path: string,
  json?: unknown,
): Promise<Reply> {
  return window.evaluate(
    async ({ method, path, json }) => {
      const state = await (
        globalThis as unknown as Window
      ).dockline.getBackendState();
      if (state.status !== "ready")
        throw new Error(`Backend not ready: ${JSON.stringify(state)}`);
      const res = await fetch(`${state.baseUrl}${path}`, {
        method,
        headers:
          json === undefined
            ? undefined
            : { "Content-Type": "application/json" },
        body: json === undefined ? undefined : JSON.stringify(json),
      });
      return {
        status: res.status,
        contentType: res.headers.get("content-type"),
        retryAfter: res.headers.get("retry-after"),
        body: await res.text(),
      };
    },
    { method, path, json },
  );
}

// The Domains view comes in a later task; this proves the domains API works from the renderer against the
// fake Hostinger (an empty portfolio, sorting by expiry, and each kind of error), and that the token never
// reaches the renderer.
test("the renderer can list (fake) Hostinger domains and gets a clear problem for each error", async ({
  wiremock,
  launchApp,
}) => {
  const { window } = await launchApp(hostingerEnv(wiremock));
  await expect(window.getByText("Backend connected")).toBeVisible();
  const seen: string[] = [];
  const call = async (
    path: string,
    method = "GET",
    json?: unknown,
  ): Promise<Reply> => {
    const reply = await api(window, method, path, json);
    seen.push(reply.body);
    return reply;
  };

  const notConnected = await call("/api/hostinger/domains");
  expect(notConnected.status).toBe(409);
  expect(notConnected.contentType).toContain("application/problem+json");
  expect(JSON.parse(notConnected.body)).toMatchObject({
    code: "HOSTINGER_NOT_CONNECTED",
  });

  await stubSubscriptionsOk(wiremock);
  expect(
    (await call("/api/hostinger/token", "PUT", { token: FAKE_HOSTINGER.token }))
      .status,
  ).toBe(200);

  await stubDomains(wiremock, []);
  const empty = await call("/api/hostinger/domains");
  expect(empty.status).toBe(200);
  expect(JSON.parse(empty.body)).toMatchObject({ domains: [] });

  await stubDomains(wiremock, [
    { domain: "later.example", expiresAt: "2028-03-01T00:00:00Z" },
    { domain: "no-expiry.example", expiresAt: null, status: "pending_setup" },
    {
      domain: "soon.example",
      expiresAt: "2026-12-01T10:00:00Z",
      createdAt: "2024-12-01T10:00:00Z",
    },
    { domain: null, expiresAt: null, status: "requested", type: "free_domain" },
  ]);
  const listed = await call("/api/hostinger/domains?refresh=true");
  expect(listed.status).toBe(200);
  const body = JSON.parse(listed.body) as {
    domains: unknown[];
    fetchedAt: string;
  };
  expect(Number.isNaN(Date.parse(body.fetchedAt))).toBe(false);
  expect(body.domains).toEqual([
    {
      domain: "soon.example",
      expiresAt: "2026-12-01T10:00:00Z",
      status: "active",
      type: "domain",
      createdAt: "2024-12-01T10:00:00Z",
    },
    expect.objectContaining({
      domain: "later.example",
      expiresAt: "2028-03-01T00:00:00Z",
    }),
    expect.objectContaining({
      domain: "no-expiry.example",
      expiresAt: null,
      status: "pending_setup",
    }),
    expect.objectContaining({
      domain: null,
      expiresAt: null,
      type: "free_domain",
    }),
  ]);

  await stubDomainsServerError(wiremock);
  const failing = await call("/api/hostinger/domains?refresh=true");
  expect(failing.status).toBe(502);
  expect(JSON.parse(failing.body)).toMatchObject({
    code: "HOSTINGER_UNAVAILABLE",
  });

  await stubDomainsUnauthorized(wiremock);
  const rejected = await call("/api/hostinger/domains?refresh=true");
  expect(rejected.status).toBe(409);
  expect(JSON.parse(rejected.body)).toMatchObject({
    code: "HOSTINGER_TOKEN_REJECTED",
  });
  const status = await call("/api/status");
  expect(JSON.parse(status.body).sources[1]).toMatchObject({
    source: "HOSTINGER",
    state: "ERROR",
  });

  // A long Retry-After is answered at once (no hanging request) with a countdown; last, because no
  // Hostinger request is sent for the next 60 s.
  await stubDomainsRateLimited(wiremock, 60);
  const limited = await call("/api/hostinger/domains?refresh=true");
  expect(limited.status).toBe(429);
  expect(limited.retryAfter).toBe("60");
  expect(JSON.parse(limited.body)).toMatchObject({
    code: "HOSTINGER_RATE_LIMITED",
    retryAfterSeconds: 60,
  });
  const limitedStatus = JSON.parse((await call("/api/status")).body).sources[1];
  expect(limitedStatus).toMatchObject({ source: "HOSTINGER", state: "LIMITED" });
  expect(limitedStatus.retryAfterSeconds).toBeGreaterThan(0);
  expect(limitedStatus.retryAfterSeconds).toBeLessThanOrEqual(60);

  const log = await call("/api/log?source=HOSTINGER");
  expect(
    (JSON.parse(log.body) as { action: string }[]).filter(
      (e) => e.action === "GET /api/domains/v1/portfolio",
    ),
  ).toHaveLength(3);

  for (const text of seen) {
    expect(text).not.toContain(FAKE_HOSTINGER.token);
  }
});
