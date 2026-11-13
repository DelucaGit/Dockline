# Dockline

A Windows desktop app that brings GitHub and Hostinger together in one window.

Planning lives in GitHub Issues — start with the PRD issue.

## Setup

To use "Connect to GitHub" you register a GitHub OAuth App once and give its Client ID to the app. Follow [docs/github-oauth-app-setup.md](docs/github-oauth-app-setup.md) (about five minutes, no client secret needed).

## Backend

The backend lives in `backend/`: Java 25, Spring Boot 4, Maven. No Maven install is needed, the Maven Wrapper (`mvnw`) downloads it.

**Java 25 is required.** If your default `java` is older, point `JAVA_HOME` at a JDK 25 first (Git Bash example):

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
```

Build and test (also exports the OpenAPI file):

```bash
cd backend
./mvnw verify
```

Run it (default port 8080, localhost only). Choose the port with `--server.port` or `SERVER_PORT`; `0` picks a free port and prints it in the log:

```bash
./mvnw spring-boot:run                              # port 8080
java -jar target/dockline-backend-0.1.0-SNAPSHOT.jar --server.port=18123
SERVER_PORT=0 java -jar target/dockline-backend-0.1.0-SNAPSHOT.jar
```

| What | URL |
| --- | --- |
| Health (`{"status":"UP"}`) | `http://localhost:<port>/actuator/health` |
| OpenAPI description (JSON) | `http://localhost:<port>/v3/api-docs` |
| Swagger UI | `http://localhost:<port>/swagger-ui.html` |

The build also writes the OpenAPI description to **`backend/openapi.json`** (committed, so the desktop app can generate its API types from it). During `verify` the build briefly starts the app on port 18089 to export it; change that with `-Dopenapi.export.port=...` if the port is busy.

## Desktop app

The desktop app lives in `desktop/`: Electron, React, TypeScript, Tailwind CSS, shadcn/ui and TanStack Query, built with electron-vite. Node 24 and npm are required.

```bash
cd desktop
npm install              # install dependencies (also downloads the Electron binary)
npm run dev              # start the app in development mode (hot reload)
npm test                 # run the frontend tests (Vitest + Testing Library)
npm run build            # type-check and build
npm run generate:api     # regenerate API types from ../backend/openapi.json
```

`generate:api` writes `desktop/src/renderer/src/api/schema.d.ts`. That file is committed; re-run the command after the backend's `openapi.json` changes. All user-facing text lives in `desktop/src/renderer/src/strings.ts`. The theme follows the Windows light/dark setting.

### How the app starts the backend

When the app opens, the Electron main process starts the backend as a child process on a free localhost port, waits until `/actuator/health` says `UP` (up to 60 seconds) and shows a loading screen meanwhile. If it fails (Java or jar missing, crash, timeout) an error screen with the reason and a Retry button is shown. When the app quits or crashes, the whole Java process tree is killed (`taskkill /T /F` on Windows), so nothing is left running.

| What | How it is found |
| --- | --- |
| Java | `DOCKLINE_JAVA` (full path to `java`/`java.exe`), else `JAVA_HOME/bin/java`, else `java` on PATH. The backend needs Java 25. |
| Backend jar | `DOCKLINE_BACKEND_JAR`, else `backend/target/dockline-backend-*.jar` (build it first with `./mvnw verify`). |

So to run the whole app from Git Bash:

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot"
(cd backend && ./mvnw verify)
cd desktop && npm run dev
```

### External API addresses

The backend reads the base URLs of the external APIs from Spring properties. The defaults are the real APIs; tests point them at a fake server (WireMock) through environment variables, which the desktop app passes on to the backend:

| Property | Environment variable | Default |
| --- | --- | --- |
| `dockline.github.api-url` | `DOCKLINE_GITHUB_API_URL` | `https://api.github.com` |
| `dockline.github.oauth-url` | `DOCKLINE_GITHUB_OAUTH_URL` | `https://github.com` |
| `dockline.hostinger.api-url` | `DOCKLINE_HOSTINGER_API_URL` | `https://developers.hostinger.com` |

### Logging and the Log API

**Log files.** The backend writes `dockline.log` to `%APPDATA%\Dockline\logs` on Windows (`~/.local/share/Dockline/logs` elsewhere). Change the folder with `--dockline.log.dir=...` or `DOCKLINE_LOG_DIR`. The file rolls over every day and at 10 MB; files older than 14 days are deleted, and all of them together stay under 100 MB (`backend/src/main/resources/logback-spring.xml`). Backend tests and the end-to-end tests write their logs to a temporary folder instead.

**Token masking.** Every line written to the log files or the console, and every log entry the API returns, goes through `SecretMasker`. It replaces GitHub tokens (`ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`, `github_pat_`), `Authorization:` values, `Bearer ...` values (how Hostinger tokens are sent) and `token=` / `"access_token":` style values with `****`. Code that stores a real token calls `SecretMasker.register(token)` so that exact value is masked everywhere too.

**Reporting a problem.** Backend code calls `ProblemLog.report(level, source, action, statusCode, message, request, response, exception)`. Any unexpected exception in a REST handler is reported automatically as an `ERROR` from `BACKEND` with its stack trace, and answered with a 500 problem response (masked). The newest 500 entries are kept in memory.

| Request | What it does |
| --- | --- |
| `GET /api/log?source=GITHUB\|HOSTINGER\|BACKEND&level=WARN\|ERROR` | Recent warnings and errors, newest first (both filters optional) |
| `DELETE /api/log` | Clears the list (`204`); the log files are kept |

**Log section (desktop).** The Log page in the app lists these entries newest first, with filters for source and level (sent to the API as query parameters), Refresh (it also refreshes when the window regains focus), Clear, and expandable request / response / stack trace. "Copy for AI" on an entry puts a readable text block with app version, time, source, action, status code, message, request, response and stack trace on the clipboard; the text is run through a second, client-side token mask (`desktop/src/renderer/src/lib/maskTokens.ts`) on top of the backend's.

Each entry: `id`, `time` (ISO-8601, UTC), `level`, `source`, `action`, `statusCode` (left out if none), `message`, and `context` with `appVersion`, `request`, `response`, `stackTrace` (each left out if none).

**Test-only endpoints.** With `DOCKLINE_TEST_ENDPOINTS=true` (or `--dockline.test-endpoints=true`) the backend also offers `POST /api/test/report-error` (query parameters `level`, `source`, `action`, `statusCode`, `message`, all optional; every text includes a fake GitHub token, so tests can check masking) and `POST /api/test/fail` (throws an unexpected exception). They are **off by default**, so they do not exist in a normal run, and are never part of `openapi.json`. The end-to-end tests turn them on through the environment, which the desktop app passes on to the backend.

The renderer talks to the main process only through the small `window.dockline` API in `desktop/src/preload/index.ts` (no Node integration in the window). The bundled Java runtime comes in a later task.

### Tokens and connection status

GitHub and Hostinger tokens are kept in **Windows Credential Manager** (service name `Dockline`, one entry per source), so they survive restarts and never end up in files, logs or API responses; every stored token is also registered with `SecretMasker`. Backend code uses the `TokenStore` interface (`save` / `read` / `delete` per source). `dockline.token-store` (or `DOCKLINE_TOKEN_STORE`) chooses the store: `credential-manager` (default) or `memory` (forgotten on exit). Backend tests and end-to-end tests always use `memory`. If Credential Manager cannot be opened, the app still starts and reports an error in the Log section.

`GET /api/status` returns `{"sources":[{"source":"GITHUB","state":"NOT_CONNECTED","message":null}, ...]}` with one entry per source; `state` is `CONNECTED`, `LIMITED`, `ERROR` or `NOT_CONNECTED`. For Hostinger a stored token means `CONNECTED` for now; a `SourceStatusProvider` bean can work out the real state of a source. GitHub has one (see below).

### Settings file and manual site–repo links

Settings that are not secrets live in one JSON file, `settings.json`, in `%APPDATA%\Dockline` on Windows (`~/.local/share/Dockline` elsewhere). Change the folder with `--dockline.settings.dir=...` or `DOCKLINE_SETTINGS_DIR`; backend tests and the end-to-end tests point it at a temporary folder, so they never touch the real file. Today it holds the manual links between a site and its GitHub repository (one repository per site):

```json
{
  "version": 1,
  "siteRepoLinks": {
    "example.com": "owner/name"
  }
}
```

Backend code uses the `SiteRepoLinkRepository` interface (`find(domain)`, `findAll()`, `save(domain, repoFullName)`, `remove(domain)`; domains are compared in lower case), so a database could replace the file later. `JsonFileSiteRepoLinkRepository` stores the links through `SettingsFile`, which owns the file:

- **Version.** `"version"` is the file format (`SettingsFile.CURRENT_VERSION`, now `1`). An older file is upgraded step by step when it is read (`SettingsFile.UPGRADES`, one step per version; empty until the format first changes) and saved in the new format. Properties this version does not know are kept.
- **Safe writes.** Every change is written to `settings.json.tmp`, flushed to disk and then moved over `settings.json` in one atomic step, so a crash never leaves a half-written file. If saving fails, `SettingsUnavailableException` is thrown and nothing changes, on disk or in memory.
- **Missing file.** The app starts with no links; the file is created by the first change.
- **Unusable file** (not JSON, no valid `version`, or written by a newer Dockline). The app starts with no links, the file is kept as `settings.corrupt-<UTC time>.json` in the same folder, and a `WARN` from `BACKEND` (action `Read settings file`) appears in the Log section.
- The file is read once at start-up; all access is thread-safe.

The REST endpoints that read and change the links are described under "Site–repo links" below. Tests: `SettingsFileTest`, `JsonFileSiteRepoLinkRepositoryTest` and `SettingsFileStartupTest`.

### Connecting to GitHub

GitHub uses the **device flow**: the app shows a short code, the user enters it on GitHub and approves, and the backend receives the token and saves it. The token never passes through the frontend. It needs the Client ID of the Dockline OAuth App: `dockline.github.client-id` / `DOCKLINE_GITHUB_CLIENT_ID` (empty by default; see [docs/github-oauth-app-setup.md](docs/github-oauth-app-setup.md)). Without it, starting the flow answers `400` with the problem detail "GitHub client ID is not configured ...", and the GitHub status is `NOT_CONNECTED` with the same message.

| Endpoint | What it does |
|---|---|
| `POST /api/github/connect` | Starts connecting (replacing any attempt already running). Answers `{userCode, verificationUri, expiresInSeconds, intervalSeconds}`; `400`/`502` problem responses when it cannot start. |
| `POST /api/github/connect/poll` | Has the user approved yet? Answers `{state, intervalSeconds, message}`, `state` = `NOT_STARTED`, `PENDING`, `CONNECTED`, `DENIED`, `EXPIRED` or `ERROR`. Safe to call every few seconds: GitHub is only asked once its interval has passed (it grows by 5 s when GitHub says `slow_down`). On `CONNECTED` the token is saved. |
| `POST /api/github/disconnect` | Deletes the token and stops any attempt (`204`). |
| `POST /api/github/recheck` | Checks the saved token with GitHub now and answers its `SourceStatus`. |

`GET /api/status` checks the saved GitHub token with `GET /user` at most once a minute (connect, disconnect and recheck skip the wait): `200` = `CONNECTED` ("Connected as LOGIN"), `401` = `ERROR` (revoked or expired, reconnect; also written to the Log section), `403`/`429` (rate limit) = `LIMITED`, and GitHub unreachable or failing = `LIMITED` (the token may be fine, so the app does not ask to reconnect). Time-dependent code uses a `Clock` bean that tests replace with `MutableClock`.

**Fake GitHub in tests.** Backend tests use `GithubStubs` (backend test sources) and end-to-end tests `desktop/e2e/support/githubStubs.ts` (`githubEnv`, `stubDeviceCode`, `stubTokenPending`, `stubTokenSlowDown`, `stubTokenDenied`, `stubTokenExpired`, `stubTokenSuccess`, `stubTokenError`, `stubUser`, `stubUserUnauthorized`, `stubUserRateLimited`, and for repositories `stubRepos(wiremock, repos, { pages })`, `stubReposUnauthorized`, `stubReposRateLimited`, `stubReposServerError`), both on WireMock.

### GitHub repositories

`GET /api/github/repositories` answers `{repositories: [{name, fullName, visibility, lastPushAt, htmlUrl, fork, archived}], fetchedAt, truncated}`: `visibility` is `PRIVATE` or `PUBLIC`, `lastPushAt` is ISO-8601 or `null` (never pushed), the list is most recently pushed first, and `fetchedAt` is when the backend asked GitHub. It calls GitHub's `GET /user/repos` (everything the user owns, collaborates on or sees through an organization, 100 per page) and follows every `rel="next"` page link, but only links on the configured GitHub API address, so the token is never sent anywhere else; it stops after 50 pages (5,000 repositories) and then sets `truncated: true`. Nothing is cached: every call (and so every "Refresh") asks GitHub again.

**Repos view (desktop).** The Repos page fetches on open and on **Refresh** only (no timer, no refetch on window focus), shows each repository with a Private/Public badge, the last push as a relative time (exact time on hover) and a "Last updated" time; clicking a name opens it on GitHub in the default browser. Its reusable pieces for the Sites and Domains views are `components/ViewHeader.tsx` (title, Last updated, Refresh), `components/ViewState.tsx` (loading / empty / error) and `lib/relativeTime.ts`.

**Repos errors.** The Repos view switches on the backend problem `code` (`lib/problemState.ts`, reusable for Sites/Domains): not connected links to Settings, a rejected token offers Reconnect (starts the device flow), rate limits and outages offer Try again; a failed load shows the error instead of the list, and never touches Hostinger.

Errors are `application/problem+json` with a stable `code` property for the UI and a readable `detail`:

| Status | `code` | When |
|---|---|---|
| `409` | `GITHUB_NOT_CONNECTED` | No GitHub token saved; GitHub is not called |
| `409` | `GITHUB_TOKEN_REJECTED` | GitHub answered `401` (revoked or expired): reconnect |
| `429` | `GITHUB_RATE_LIMITED` | GitHub's rate limit (`429`, or `403` with `x-ratelimit-remaining: 0` or `Retry-After`); `Retry-After` header in seconds when known |
| `502` | `GITHUB_UNAVAILABLE` | GitHub unreachable, timed out, `5xx`/other errors, an unreadable answer or a next-page link to another host |

Every GitHub failure is written to the Log section (action `GET /user/repos`, masked request and response) and updates the GitHub status at once (`ERROR` for a rejected token, `LIMITED` otherwise), without waiting for the 60-second status cache. Other data endpoints can report problems the same way by throwing `ApiProblem`.

**Connections UI (desktop).** Settings has a GitHub section: "Connect to GitHub" shows the short code (with "Copy code"), opens GitHub's page in the default browser and shows the address as a link in case the browser did not open, then polls `POST /api/github/connect/poll` (at the interval the backend returns, at least every second) until it says `CONNECTED`. Denied, expired or failed attempts show the backend's message and "Try again"; a missing client ID shows the readable `400` message. "Disconnect" asks for confirmation first. At the bottom of the sidebar, a Connections block shows a dot for GitHub and Hostinger from `GET /api/status` (green = connected, yellow = limited, red = error or not connected; each has a text such as "GitHub: not connected" for screen readers and as tooltip; a click opens Settings). It refreshes every 30 seconds, when the window gets focus again, and after connect or disconnect. Opening the browser goes through `window.dockline.openExternal(url)`, which the main process only allows for `https:` addresses; with `DOCKLINE_E2E_NO_BROWSER=1` (set by the end-to-end test fixture) it records the address in `globalThis.__docklineOpenedUrls` instead of opening a browser. The end-to-end tests keep their tokens in memory, so "the connection survives a restart" is not covered there; it depends on Credential Manager.

**Revoked token.** When GitHub answers `401` the dot turns red ("GitHub: error — reconnect needed") and both Settings and the sidebar offer "Reconnect". The sidebar button opens Settings and starts the device flow at once; a successful approval replaces the old token and the dot turns green. Opening Settings and focusing the window call `POST /api/github/recheck`, so a revoked token shows up without waiting for the 60-second cache. Restart persistence is covered by the backend `CredentialManagerTokenStoreTest`; to check it by hand, connect, quit Dockline, start it again and look for a green GitHub dot.

### Connecting to Hostinger

Hostinger has no "log in with Hostinger", so the user pastes an API token created in hPanel. The backend checks it with Hostinger (`GET /api/billing/v1/subscriptions`, a read-only list every account has) **before** saving it, so a bad token is never stored and never replaces a working one. The token never appears in a response, the Log section or the log file.

| Endpoint | What it does |
|---|---|
| `PUT /api/hostinger/token` with `{"token": "..."}` | Checks the token, then saves it. `200` answers the new `SourceStatus` (`CONNECTED`). Nothing is saved on: `400` blank/missing/over 1024 characters, `422` Hostinger rejected the token (401/403), `429` Hostinger's rate limit (passes on `Retry-After`), `502` Hostinger unreachable, timed out or answered 5xx. Errors are `application/problem+json` with a readable `detail`. |
| `DELETE /api/hostinger/token` | Deletes the token (`204`, also when none was saved). |
| `POST /api/hostinger/recheck` | Checks the saved token with Hostinger now and answers its `SourceStatus`. |

`GET /api/status` checks the saved Hostinger token at most once a minute (saving, deleting and recheck skip the wait): `200` = `CONNECTED`, `401`/`403` = `ERROR` (revoked or expired, replace the token; also written to the Log section), `429` (with `retryAfterSeconds`, see below) or Hostinger unreachable/failing = `LIMITED`. All Hostinger calls go through `HostingerClient`. Tests fake Hostinger with `HostingerStubs` (backend) and `desktop/e2e/support/hostingerStubs.ts` (`hostingerEnv`, `stubSubscriptionsOk`, `stubUnauthorized`, `stubRateLimited`, `stubServerError`, `stubConnectionReset`).

**Hostinger in Settings (desktop).** The Hostinger section has a password-type token field (never shown again once saved; cleared after every attempt), a link to hPanel's API page (`https://hpanel.hostinger.com/api`, opened via `openExternal`), "Save" (busy while the backend checks the token), the backend's message on `400`/`422`/`429`/`502`, and "Disconnect" with a confirmation. Opening Settings calls `POST /api/hostinger/recheck`. Tests: `SettingsHostinger.test.tsx` and `e2e/hostinger-connect.spec.ts`.

**Replacing a revoked token (desktop).** When Hostinger answers 401 for the saved token, the Hostinger dot turns red ("Hostinger: error — token needs replacing") and a "Replace token" button appears next to it; it opens Settings with the token field focused. A new token is checked before it replaces the old one: an invalid one shows the message and the old state is kept. The sidebar rechecks Hostinger when the window gets focus. That the token survives a restart is not covered by the end-to-end tests (in-memory store); check by hand: save a token, quit, start again, the dot is green.

### Hostinger websites

`GET /api/hostinger/sites` answers `{sites: [{domain, username, orderId, websiteType, rootDirectory, enabled, createdAt}], fetchedAt, truncated}`, sorted by domain (websites without a domain last). `domain`, `username`, `orderId`, `websiteType`, `rootDirectory` and `createdAt` may be `null` (Hostinger leaves `username` and `rootDirectory` empty for non-CloudLinux websites such as Builder or Horizons, and `domain` for websites without one); `enabled` is `false` when Hostinger has suspended the website; `fetchedAt` is when the backend really asked Hostinger (a reused copy keeps its original time; the UI shows it as "Last updated"). It calls Hostinger's `GET /api/hosting/v1/websites?page=N&per_page=100` through `HostingerClient`, page 1, 2, ... until `meta.total` websites are read or a page comes back short, always on the configured Hostinger address (Hostinger sends no page links); it stops after 10 pages and then sets `truncated: true`. Hostinger's list includes websites shared with the user by other Hostinger clients; `orderId` tells which hosting plan a website belongs to.

Errors mirror the GitHub ones: `409` `HOSTINGER_NOT_CONNECTED` (no token; Hostinger not called, nothing logged), `409` `HOSTINGER_TOKEN_REJECTED` (Hostinger answered `401`/`403`: replace the token; status `ERROR`), `429` `HOSTINGER_RATE_LIMITED` (`Retry-After` header and `retryAfterSeconds` in the body; status `LIMITED`; see "too many requests" below), `502` `HOSTINGER_UNAVAILABLE` (unreachable, timeout, `5xx`/other errors or an unreadable answer; status `LIMITED`). Every failure is written to the Log section (action `GET /api/hosting/v1/websites`, masked) and updates the Hostinger status at once. Tests: `HostingerSitesApiTest` and `e2e/hostinger-sites-api.spec.ts`, with the fakes `HostingerStubs.websites(...)` and `stubSites(wiremock, sites, { pages })`, `stubSitesUnauthorized`, `stubSitesRateLimited`, `stubSitesServerError`.

### Hostinger domains

`GET /api/hostinger/domains` answers `{domains: [{domain, expiresAt, status, type, createdAt}], fetchedAt}`, sorted by earliest expiry first (domains without an expiry date last, then by name). `domain`, `expiresAt`, `status`, `type` and `createdAt` may be `null` (`domain` is `null` for a free domain that is not claimed yet; `expiresAt` when Hostinger gives no date). `status` is Hostinger's own value (`active`, `pending_setup`, `expired`, `requested`, `pending_verification`, `deleted`, `suspended`, `failed`) and `type` is `domain`, `free_domain`, `domain_transfer` or `free_domain_transfer`. Hostinger sends no "last updated" time per domain, so `fetchedAt` is the "Last updated" of the list: when the backend really asked Hostinger (a reused copy keeps its original time). It calls Hostinger's `GET /api/domains/v1/portfolio` (one JSON array, no paging) through `HostingerClient`, so the 50 requests per minute cap, the 60-second cache, `?refresh=true` and the `429` handling below apply as for websites.

Errors are the same as for websites: `409` `HOSTINGER_NOT_CONNECTED`, `409` `HOSTINGER_TOKEN_REJECTED`, `429` `HOSTINGER_RATE_LIMITED` (`Retry-After` header and `retryAfterSeconds`), `502` `HOSTINGER_UNAVAILABLE`. Every failure is written to the Log section (action `GET /api/domains/v1/portfolio`, masked) and updates the Hostinger status at once. Tests: `HostingerDomainsApiTest` and `e2e/hostinger-domains-api.spec.ts`, with the fakes `HostingerStubs.domains(...)` and `stubDomains(wiremock, domains)`, `stubDomainsUnauthorized`, `stubDomainsRateLimited`, `stubDomainsServerError`.

**Request cap and cache.** Hostinger allows about 90 requests a minute and can block the IP address after repeated violations, so `HostingerClient` (the one place every Hostinger request goes through) protects the account in two ways:

- **At most 50 requests per rolling minute**, counted across every feature (token check, status dot, recheck, websites, domains). A request over the cap is not refused: it waits until the oldest request in the window is a minute old, then goes out. If the cap stays full for a whole minute, the call fails like an unreachable Hostinger (`502` `HOSTINGER_UNAVAILABLE`, yellow dot). `HostingerRateLimiter`.
- **Successful answers are kept in memory for 60 seconds** (`HostingerResponseCache`, at most 500 answers, keyed by method, URL and a hash of the token, never the token itself) and reused for the website and domain lists, so opening Sites or Domains again within a minute does not call Hostinger. Answers about a website's Git connection are reused for five minutes instead (see "Site–repo links"). Errors are never kept. Saving or removing the token clears the cache. `GET /api/hostinger/sites?refresh=true` (and `/domains`) skips the saved copy, asks Hostinger (this still counts towards the 50) and keeps the new answer. Checking a token (`PUT /api/hostinger/token`, `POST /api/hostinger/recheck`) always asks Hostinger; the status dot keeps its own once-a-minute result.

Both rules use the `Clock` bean, and waiting goes through the `Waiter` bean (`Thread.sleep` in the app). Tests use `MutableClock` and `ClockAdvancingWaiter`, which moves the clock instead of sleeping; `HostingerCapAndCacheApiTest` proves the cap, the cache, expiry, refresh, that errors and token checks are not cached, and that a token change clears the cache, by counting the calls WireMock receives.

**Sites view (desktop).** Lists every Hostinger website (domain, type, "Suspended" when disabled) with "Refresh" and "Last updated". Opening the view reuses the backend's 60-second copy; "Refresh" and "Try again" send `?refresh=true`. States: not connected (button to Settings), rejected token ("Replace token"), a readable error with "Try again", and on `HOSTINGER_RATE_LIMITED` "Rate limited, retrying in X s", counting down and asking again by itself (the Hostinger dot is yellow meanwhile). Tests: `SitesPage.test.tsx` and `e2e/sites-view.spec.ts`.

**Domains view (desktop).** Lists every Hostinger domain (name and expiry date), earliest expiry first; a domain without a name shows "(no name)", one without a date "No expiry date". A domain that expires within 30 days gets an amber "Expiring soon" label, one whose date has passed (or that Hostinger marks `expired`) a red "Expired" label; both have their own shades for light and dark theme. "Refresh", "Last updated" and the not-connected, "Replace token" and "Rate limited, retrying in X s" states work like in the Sites view. Unlike Sites, when a request fails the last known domains stay visible below the error message. Tests: `DomainsPage.test.tsx` and `e2e/domains-view.spec.ts`.

**Hostinger's "too many requests" (429).** If Hostinger still answers `429`, `HostingerClient` handles it for every feature:

- The wait is Hostinger's `Retry-After` header (seconds, or an HTTP date), kept between 1 and 120 seconds. A missing or unreadable header means `dockline.hostinger.default-retry-after` (default `30s`; env `DOCKLINE_HOSTINGER_DEFAULT_RETRY_AFTER`).
- During the wait **no Hostinger request is sent at all**, from any feature or thread (`HostingerRateLimiter` keeps a shared "blocked until" time).
- Each `429` is written to the Log section once as a `WARN` (source `HOSTINGER`, action `GET <path>`, status code `429`, masked request and response).
- After the wait the same request is sent again, at most 3 attempts in all; if Hostinger keeps answering `429`, the call fails with `429` `HOSTINGER_RATE_LIMITED`.
- **Short waits (up to 10 s) are waited out inside the request; longer ones are answered at once.** A request to Dockline never hangs for minutes: when the wait left is longer than 10 seconds, `GET /api/hostinger/sites`, `GET /api/hostinger/domains` (and `PUT /api/hostinger/token`) answer `429` immediately with the `Retry-After` header and a `retryAfterSeconds` number in the problem body (`{"code":"HOSTINGER_RATE_LIMITED","detail":"Hostinger asked us to slow down; retrying in 23 s.","retryAfterSeconds":23,...}`). The UI counts down and asks again when the wait is over.
- While the wait lasts, `GET /api/status` reports Hostinger as `LIMITED` with `retryAfterSeconds` (seconds left, rounded up) and the message "Hostinger asked us to slow down; retrying in 23 s.", without calling Hostinger; afterwards it checks the token again. `retryAfterSeconds` is `null` at all other times.

Tests: `HostingerRetryAfterApiTest` (the wait and retry, HTTP-date and default waits, the immediate `429` and no calls during a long wait, the countdown in the status, one log entry per `429`, bounded retries). The E2E fake `stubSitesRateLimitedOnce(wiremock, seconds)` answers `429` once and then lets `stubSites` answer (a WireMock scenario), for "Hostinger says slow down, then recovers".

### Site–repo links

Which GitHub repository belongs to a site. A site is identified by its domain (case does not matter). The link comes from one of two places, and Hostinger always wins:

1. **Hostinger's Git connection** (`source: "HOSTINGER"`). Found without any setup, and it cannot be changed in Dockline.
2. **A manual link** saved in the settings file (`source: "MANUAL"`). While a Hostinger Git connection exists, a saved manual link is not shown, but it is kept and shows again if the connection is removed.

Otherwise `source` is `"NONE"`.

| Endpoint | What it does |
|---|---|
| `GET /api/sites/{domain}` | The site detail. Add `?refresh=true` to ask Hostinger again about this site's Git connection. |
| `GET /api/sites/links` | The link of every site in one call, for the Sites list. |
| `PUT /api/sites/{domain}/repo` | Sets or changes the manual link. Body `{"repoFullName": "owner/name"}`. Answers like the site detail. |
| `DELETE /api/sites/{domain}/repo` | Removes the manual link. `204`, also when there was none. Needs neither Hostinger nor GitHub. |

**Site detail.** `GET /api/sites/example.com` answers:

```json
{
  "domain": "example.com",
  "repo": { "fullName": "octocat/my-site", "htmlUrl": "https://github.com/octocat/my-site" },
  "source": "HOSTINGER",
  "hostingerChecked": true,
  "repoWarning": null,
  "problems": []
}
```

- `repo` is `null` when `source` is `NONE`.
- `repoWarning` is `{code, message}` when GitHub says the linked repository is missing or not accessible (checked with GitHub's `GET /repos/{owner}/{repo}`). This is a warning, not an error: the answer is still `200` and the link is kept. Codes: `REPO_NOT_FOUND` (`404`: deleted, or private and not visible to the connected user), `REPO_NOT_ACCESSIBLE` (`403` that is not a rate limit, or `451`), `REPO_MOVED` (`301`: renamed or transferred).
- `problems` lists the sources that could not be asked, at most one entry each: `{source, code, message, retryAfterSeconds}` with `source` `HOSTINGER` or `GITHUB` and the same codes as the list endpoints (`HOSTINGER_NOT_CONNECTED`, `HOSTINGER_TOKEN_REJECTED`, `HOSTINGER_RATE_LIMITED`, `HOSTINGER_UNAVAILABLE`, `GITHUB_NOT_CONNECTED`, `GITHUB_TOKEN_REJECTED`, `GITHUB_RATE_LIMITED`, `GITHUB_UNAVAILABLE`). **One failing source does not break the other**: the answer is still `200`.
  - GitHub cannot be asked: the link is shown as usual, without the repository check (`repoWarning` is `null`).
  - Hostinger cannot be asked: `hostingerChecked` is `false` and the answer comes from the saved manual links alone (`MANUAL` or `NONE`).
- The only error of the detail is `404` `SITE_NOT_FOUND`: Hostinger answered and has no website with that domain.
- Failures are written to the Log section and update the status dots exactly as for the list endpoints ("not connected" is not logged; a repository warning is not logged either).

**Setting a manual link.** `PUT /api/sites/{domain}/repo` asks Hostinger afresh whether the site is Git-connected, then saves. It does not need GitHub: a repository that does not exist is saved and comes back with a `repoWarning`. Errors are problem responses with a `code`:

| Status | `code` | When |
|---|---|---|
| `400` | `INVALID_REPOSITORY` | `repoFullName` is missing or not `owner/name` |
| `404` | `SITE_NOT_FOUND` | Hostinger has no website with that domain |
| `409` | `SITE_GIT_CONNECTED` | The site is Git-connected in Hostinger; a manual link cannot be set (the `detail` names the connected repository) |
| `409` / `429` / `502` | `HOSTINGER_NOT_CONNECTED`, `HOSTINGER_TOKEN_REJECTED`, `HOSTINGER_RATE_LIMITED`, `HOSTINGER_UNAVAILABLE` | Hostinger could not be asked, so Dockline cannot tell whether the site is Git-connected. Nothing is saved |
| `500` | `SETTINGS_UNAVAILABLE` | The settings file could not be written (also for `DELETE`); nothing changed |

**Links for the Sites list.** `GET /api/sites/links` always answers `200`:

```json
{
  "links": [
    { "domain": "example.com", "repo": { "fullName": "octocat/my-site", "htmlUrl": "https://github.com/octocat/my-site" }, "source": "HOSTINGER", "hostingerChecked": true },
    { "domain": "plain.example", "repo": null, "source": "NONE", "hostingerChecked": true }
  ],
  "complete": true,
  "problems": []
}
```

One entry per Hostinger website that has a domain, sorted by domain. GitHub is not asked here (`htmlUrl` is built from the name; the repository check belongs to the detail). `complete` is `true` when every entry has `hostingerChecked: true`. When Hostinger cannot list the websites, `problems` holds one `HOSTINGER` entry and `links` holds the saved manual links, so they stay reachable.

**How a Git connection is found.** Hostinger has two kinds, on two endpoints, and neither lists the other's; `HostingerGitConnections` reads both through `HostingerClient`. The path parameters `username` and `domain` come from the website list.

- `GET /api/hosting/v1/accounts/{username}/websites/{domain}/git/auto-deployments/settings`: auto-deployment through Hostinger's GitHub app. Answers `{installation_uuid, is_enabled, owner, repository, branch, directory}`, all `null` when not set up. A paused one (`is_enabled: false`) still counts. Because the same feature exists for GitLab, the provider of the installation is looked up in `GET /api/hosting/v1/git/installations`; an installation that is not listed is taken to be GitHub.
- `GET /api/hosting/v1/accounts/{username}/websites/{domain}/git/repositories`: repositories cloned in hPanel's Git section. Answers an array of `{repository_url, branch, directory, webhook}`. `repository_url` is read in every github.com form: `https://github.com/owner/name(.git)`, with masked credentials (`https://***@github.com/...`, `https://***:***@github.com/...`), `git@github.com:owner/name.git`, `ssh://git@github.com(:22)/...` and `git://`. With several GitHub repositories the one in the document root (empty `directory`) wins, else the first.

The auto-deployment is asked first and wins; the Git section is only asked when it has no GitHub repository. These count as **not Git-connected** (never an error), so such a site can still get a manual link:

- a website without a `username` or `domain` (it cannot be asked about);
- a repository on another host (GitLab, Bitbucket, a private server): Dockline only links GitHub repositories;
- a website for which Hostinger answers `404` or `422` on these endpoints.

**Staying under the request cap.** Finding a Git connection costs one or two Hostinger requests per site (plus one for the installations), so:

- The answers are reused for **five minutes** (`HostingerClient.GIT_TTL`) instead of 60 seconds: a Git connection changes only when the user changes it in hPanel. `GET /api/sites/{domain}?refresh=true` asks again about that one site.
- `GET /api/sites/links` sends at most **40 Git requests per call** (`SiteLinks.MAX_REQUESTS_PER_LIST`). With 20 sites or fewer everything is answered at once: at most 40 requests plus the website list and the installations, under the cap of 50 a minute. With more sites, the rest come back with `hostingerChecked: false` (`complete: false`) and are filled in by a later call, which only asks about the sites not answered yet.
- Opening the Sites view again within five minutes sends no Git request at all.
- The list stops asking at the first Hostinger failure (one `problems` entry) instead of repeating a failing request for every site.
- Everything still goes through `HostingerClient`, so the cap of 50 a minute and the `429` handling above apply whatever happens.

Tests: `SiteLinksApiTest` (both kinds of Git connection, other Git hosts, set / change / remove, the refusal, repository warnings, each source failing on its own, the request counts and the five-minute reuse), `HostingerGitConnectionsTest` (the address forms) and `e2e/site-links-api.spec.ts`. Fakes: `HostingerStubs.gitAutoDeployment(...)`, `gitRepositories(...)`, `gitNotConnected(...)`, `gitNoAutoDeployment(...)`, `gitInstallations(...)`, `gitServerError(...)`, `gitUnauthorized(...)`, `gitRateLimited(...)` and `GithubStubs.repository(...)`, `repositoryNotFound(...)`, `repositoryError(...)`; for the end-to-end tests `stubGitAutoDeployment`, `stubGitRepositories`, `stubGitNotConnected`, `stubGitNoAutoDeployment`, `stubGitInstallations`, `stubGitServerError` (`hostingerStubs.ts`) and `stubRepository`, `stubRepositoryNotFound`, `stubRepositoryError` (`githubStubs.ts`). The shapes follow Hostinger's OpenAPI document (`github.com/hostinger/api`, `openapi.json`); the real API is never called.

## End-to-end tests

The end-to-end tests live in `desktop/e2e/`. [Playwright](https://playwright.dev) starts the **real** built Electron app, which starts the **real** backend jar, and checks what the user sees. They sit inside `desktop/` because they need its Electron binary and its built app (`out/`), so there is only one `package.json` to install.

Fake external APIs: `e2e/support/wiremock.ts` starts a [WireMock](https://wiremock.org) server (the standalone jar from the `wiremock` npm package, run with the same Java; no Docker needed) on a free port, with helpers to add stubs and read the requests it got. In a test, ask for the `wiremock` fixture and pass `wiremock.url` to the app with `launchApp({ DOCKLINE_GITHUB_API_URL: wiremock.url })`. By default every external API points at a closed local port, so a test can never reach the real GitHub or Hostinger.

Run them locally (Git Bash):

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot"   # Java 25, used by the backend and WireMock
(cd backend && ./mvnw -DskipTests package)    # build the backend jar first (or ./mvnw verify)
cd desktop
npm install
npm run test:e2e                              # builds the app, then runs Playwright
npx playwright test e2e/app-start.spec.ts     # one file only (after a build)
```

## Continuous integration

`.github/workflows/ci.yml` runs on every pull request and on pushes to `main`:

| Job | Runner | What |
| --- | --- | --- |
| Backend build + tests | Linux | `./mvnw verify`; fails if the committed `openapi.json` is out of date |
| Desktop typecheck, build + unit tests | Linux | `npm run build`, `npm test`; fails if `schema.d.ts` does not match `openapi.json` |
| End-to-end tests | Windows | builds the jar and runs `npm run test:e2e` (Dockline is a Windows app) |

No secrets or real tokens are used; external APIs are only ever faked.
