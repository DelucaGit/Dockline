import type { Page } from "@playwright/test";
import { expect, test } from "./support/fixtures";
import {
  FAKE_GITHUB,
  githubEnv,
  stubDeviceCode,
  stubRepository,
  stubRepositoryNotFound,
  stubTokenSuccess,
  stubUser,
} from "./support/githubStubs";
import {
  FAKE_HOSTINGER,
  hostingerEnv,
  stubGitAutoDeployment,
  stubGitInstallations,
  stubGitNoAutoDeployment,
  stubGitNotConnected,
  stubGitRepositories,
  stubGitServerError,
  stubSites,
  stubSubscriptionsOk,
} from "./support/hostingerStubs";

interface Reply {
  status: number;
  contentType: string | null;
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
        body: await res.text(),
      };
    },
    { method, path, json },
  );
}

// The site detail view comes in a later task; this proves the site–repo link API works from the renderer
// against the fake Hostinger and GitHub: a site Git-connected by auto-deployment, one Git-connected through
// hPanel's Git section, and one that is not connected and gets a manual link. No token reaches the renderer.
test("the renderer can read each site's repo link and set, change and remove a manual link", async ({
  wiremock,
  launchApp,
}) => {
  const { window } = await launchApp({
    ...hostingerEnv(wiremock),
    ...githubEnv(wiremock),
  });
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
  const json = async (
    path: string,
    method = "GET",
    body?: unknown,
  ): Promise<Record<string, unknown>> => {
    const reply = await call(path, method, body);
    expect(reply.status).toBe(200);
    return JSON.parse(reply.body) as Record<string, unknown>;
  };

  // Nothing connected yet: the list still answers, and says why it is empty.
  expect(await json("/api/sites/links")).toEqual({
    links: [],
    complete: false,
    problems: [
      expect.objectContaining({
        source: "HOSTINGER",
        code: "HOSTINGER_NOT_CONNECTED",
      }),
    ],
  });

  await stubSubscriptionsOk(wiremock);
  expect(
    (await call("/api/hostinger/token", "PUT", { token: FAKE_HOSTINGER.token }))
      .status,
  ).toBe(200);
  await stubSites(wiremock, [
    { domain: "shop.example" },
    { domain: "blog.example" },
    { domain: "plain.example" },
  ]);
  // shop: auto-deployment through Hostinger's GitHub app; blog: cloned in hPanel's Git section; plain: nothing.
  await stubGitAutoDeployment(wiremock, "shop.example", {
    owner: "octocat",
    repository: "shop",
  });
  await stubGitInstallations(wiremock, "github");
  await stubGitNoAutoDeployment(wiremock, "blog.example");
  await stubGitRepositories(wiremock, "blog.example", [
    "git@github.com:octocat/blog.git",
  ]);
  await stubGitNotConnected(wiremock, "plain.example");

  // GitHub is not connected: the link from Hostinger is shown anyway, with a problem for GitHub only.
  expect(await json("/api/sites/shop.example")).toEqual({
    domain: "shop.example",
    repo: {
      fullName: "octocat/shop",
      htmlUrl: "https://github.com/octocat/shop",
    },
    source: "HOSTINGER",
    hostingerChecked: true,
    repoWarning: null,
    problems: [
      {
        source: "GITHUB",
        code: "GITHUB_NOT_CONNECTED",
        message: expect.any(String),
        retryAfterSeconds: null,
      },
    ],
  });

  await stubDeviceCode(wiremock);
  await stubTokenSuccess(wiremock);
  await stubUser(wiremock);
  await call("/api/github/connect", "POST");
  await expect
    .poll(
      async () =>
        (
          JSON.parse(
            (await call("/api/github/connect/poll", "POST")).body,
          ) as { state: string }
        ).state,
      { intervals: [500], timeout: 10_000 },
    )
    .toBe("CONNECTED");

  await stubRepository(wiremock, "octocat/shop");
  expect(await json("/api/sites/shop.example")).toMatchObject({
    source: "HOSTINGER",
    repo: { fullName: "octocat/shop" },
    repoWarning: null,
    problems: [],
  });

  // The blog's repository no longer exists on GitHub: a warning, not an error.
  await stubRepositoryNotFound(wiremock, "octocat/blog");
  expect(await json("/api/sites/BLOG.example")).toMatchObject({
    domain: "blog.example",
    source: "HOSTINGER",
    repo: {
      fullName: "octocat/blog",
      htmlUrl: "https://github.com/octocat/blog",
    },
    repoWarning: { code: "REPO_NOT_FOUND", message: expect.any(String) },
    problems: [],
  });

  // A manual link: none, set, change, remove.
  expect(await json("/api/sites/plain.example")).toEqual({
    domain: "plain.example",
    repo: null,
    source: "NONE",
    hostingerChecked: true,
    repoWarning: null,
    problems: [],
  });
  await stubRepository(wiremock, "octocat/plain");
  expect(
    await json("/api/sites/plain.example/repo", "PUT", {
      repoFullName: "octocat/plain",
    }),
  ).toMatchObject({
    source: "MANUAL",
    repo: {
      fullName: "octocat/plain",
      htmlUrl: "https://github.com/octocat/plain",
    },
    repoWarning: null,
  });

  // A Git-connected site cannot get a manual link.
  const refused = await call("/api/sites/shop.example/repo", "PUT", {
    repoFullName: "octocat/plain",
  });
  expect(refused.status).toBe(409);
  expect(refused.contentType).toContain("application/problem+json");
  expect(JSON.parse(refused.body)).toMatchObject({
    code: "SITE_GIT_CONNECTED",
    detail: expect.stringContaining("octocat/shop"),
  });

  // For the Sites list: every site's link in one call.
  expect(await json("/api/sites/links")).toEqual({
    links: [
      {
        domain: "blog.example",
        repo: {
          fullName: "octocat/blog",
          htmlUrl: "https://github.com/octocat/blog",
        },
        source: "HOSTINGER",
        hostingerChecked: true,
      },
      {
        domain: "plain.example",
        repo: {
          fullName: "octocat/plain",
          htmlUrl: "https://github.com/octocat/plain",
        },
        source: "MANUAL",
        hostingerChecked: true,
      },
      {
        domain: "shop.example",
        repo: {
          fullName: "octocat/shop",
          htmlUrl: "https://github.com/octocat/shop",
        },
        source: "HOSTINGER",
        hostingerChecked: true,
      },
    ],
    complete: true,
    problems: [],
  });

  await stubRepositoryNotFound(wiremock, "octocat/renamed");
  expect(
    await json("/api/sites/plain.example/repo", "PUT", {
      repoFullName: "octocat/renamed",
    }),
  ).toMatchObject({
    source: "MANUAL",
    repo: { fullName: "octocat/renamed" },
    repoWarning: { code: "REPO_NOT_FOUND" },
  });

  const invalid = await call("/api/sites/plain.example/repo", "PUT", {
    repoFullName: "not a repo",
  });
  expect(invalid.status).toBe(400);
  expect(JSON.parse(invalid.body)).toMatchObject({ code: "INVALID_REPOSITORY" });
  const unknown = await call("/api/sites/unknown.example");
  expect(unknown.status).toBe(404);
  expect(JSON.parse(unknown.body)).toMatchObject({ code: "SITE_NOT_FOUND" });

  // Hostinger's Git endpoints fail: the manual link is still shown, with a problem for Hostinger only.
  await stubGitServerError(wiremock, "plain.example");
  expect(await json("/api/sites/plain.example?refresh=true")).toMatchObject({
    source: "MANUAL",
    hostingerChecked: false,
    repo: { fullName: "octocat/renamed" },
    problems: [{ source: "HOSTINGER", code: "HOSTINGER_UNAVAILABLE" }],
  });

  // Removing needs neither Hostinger nor GitHub.
  expect((await call("/api/sites/plain.example/repo", "DELETE")).status).toBe(
    204,
  );
  expect(await json("/api/sites/plain.example")).toMatchObject({
    source: "NONE",
    repo: null,
  });

  for (const text of seen) {
    expect(text).not.toContain(FAKE_HOSTINGER.token);
    expect(text).not.toContain(FAKE_GITHUB.accessToken);
  }
});
