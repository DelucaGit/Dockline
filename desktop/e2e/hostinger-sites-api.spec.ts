import type { Page } from "@playwright/test";
import { expect, test } from "./support/fixtures";
import {
  FAKE_HOSTINGER,
  hostingerEnv,
  stubSites,
  stubSitesRateLimited,
  stubSitesRateLimitedOnce,
  stubSitesServerError,
  stubSitesUnauthorized,
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

// The Sites view comes in a later task; this proves the sites API works from the renderer against the
// fake Hostinger (several pages, an empty plan, and each kind of error), and that the token never reaches
// the renderer.
test("the renderer can list (fake) Hostinger websites and gets a clear problem for each error", async ({
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

  const notConnected = await call("/api/hostinger/sites");
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

  await stubSites(wiremock, []);
  const empty = await call("/api/hostinger/sites");
  expect(empty.status).toBe(200);
  expect(JSON.parse(empty.body)).toMatchObject({ sites: [], truncated: false });

  await stubSites(
    wiremock,
    [
      {
        domain: "shop.example",
        username: "u100",
        orderId: 111,
        createdAt: "2025-06-01T08:00:00+02:00",
      },
      { domain: "blog.example", enabled: false },
      { domain: null, username: null, websiteType: "horizons" },
    ],
    { pages: 3 },
  );
  const listed = await call("/api/hostinger/sites?refresh=true");
  expect(listed.status).toBe(200);
  const body = JSON.parse(listed.body) as {
    sites: unknown[];
    fetchedAt: string;
    truncated: boolean;
  };
  expect(body.truncated).toBe(false);
  expect(Number.isNaN(Date.parse(body.fetchedAt))).toBe(false);
  expect(body.sites).toEqual([
    expect.objectContaining({ domain: "blog.example", enabled: false }),
    {
      domain: "shop.example",
      username: "u100",
      orderId: 111,
      websiteType: "wordpress",
      rootDirectory: "/home/u100/domains/shop.example/public_html",
      enabled: true,
      createdAt: "2025-06-01T06:00:00Z",
    },
    expect.objectContaining({
      domain: null,
      username: null,
      websiteType: "horizons",
    }),
  ]);

  await stubSitesServerError(wiremock);
  const failing = await call("/api/hostinger/sites?refresh=true");
  expect(failing.status).toBe(502);
  expect(JSON.parse(failing.body)).toMatchObject({
    code: "HOSTINGER_UNAVAILABLE",
  });

  await stubSitesUnauthorized(wiremock);
  const rejected = await call("/api/hostinger/sites?refresh=true");
  expect(rejected.status).toBe(409);
  expect(JSON.parse(rejected.body)).toMatchObject({
    code: "HOSTINGER_TOKEN_REJECTED",
  });
  const status = await call("/api/status");
  expect(JSON.parse(status.body).sources[1]).toMatchObject({
    source: "HOSTINGER",
    state: "ERROR",
  });

  // Hostinger says "slow down" once with a short Retry-After: the backend waits (1 s) and retries.
  await stubSites(wiremock, [{ domain: "shop.example" }]);
  await stubSitesRateLimitedOnce(wiremock, 1);
  const retried = await call("/api/hostinger/sites?refresh=true");
  expect(retried.status).toBe(200);
  expect(JSON.parse(retried.body)).toMatchObject({
    sites: [expect.objectContaining({ domain: "shop.example" })],
  });

  // A long Retry-After is answered at once (no hanging request) with a countdown; last, because no
  // Hostinger request is sent for the next 60 s.
  await stubSitesRateLimited(wiremock, 60);
  const limited = await call("/api/hostinger/sites?refresh=true");
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
      (e) => e.action === "GET /api/hosting/v1/websites",
    ),
  ).toHaveLength(4);

  for (const text of seen) {
    expect(text).not.toContain(FAKE_HOSTINGER.token);
  }
});
