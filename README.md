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

### Connecting to GitHub

GitHub uses the **device flow**: the app shows a short code, the user enters it on GitHub and approves, and the backend receives the token and saves it. The token never passes through the frontend. It needs the Client ID of the Dockline OAuth App: `dockline.github.client-id` / `DOCKLINE_GITHUB_CLIENT_ID` (empty by default; see [docs/github-oauth-app-setup.md](docs/github-oauth-app-setup.md)). Without it, starting the flow answers `400` with the problem detail "GitHub client ID is not configured ...", and the GitHub status is `NOT_CONNECTED` with the same message.

| Endpoint | What it does |
|---|---|
| `POST /api/github/connect` | Starts connecting (replacing any attempt already running). Answers `{userCode, verificationUri, expiresInSeconds, intervalSeconds}`; `400`/`502` problem responses when it cannot start. |
| `POST /api/github/connect/poll` | Has the user approved yet? Answers `{state, intervalSeconds, message}`, `state` = `NOT_STARTED`, `PENDING`, `CONNECTED`, `DENIED`, `EXPIRED` or `ERROR`. Safe to call every few seconds: GitHub is only asked once its interval has passed (it grows by 5 s when GitHub says `slow_down`). On `CONNECTED` the token is saved. |
| `POST /api/github/disconnect` | Deletes the token and stops any attempt (`204`). |
| `POST /api/github/recheck` | Checks the saved token with GitHub now and answers its `SourceStatus`. |

`GET /api/status` checks the saved GitHub token with `GET /user` at most once a minute (connect, disconnect and recheck skip the wait): `200` = `CONNECTED` ("Connected as LOGIN"), `401` = `ERROR` (revoked or expired, reconnect; also written to the Log section), `403`/`429` (rate limit) = `LIMITED`, and GitHub unreachable or failing = `LIMITED` (the token may be fine, so the app does not ask to reconnect). Time-dependent code uses a `Clock` bean that tests replace with `MutableClock`.

**Fake GitHub in tests.** Backend tests use `GithubStubs` (backend test sources) and end-to-end tests `desktop/e2e/support/githubStubs.ts` (`githubEnv`, `stubDeviceCode`, `stubTokenPending`, `stubTokenSlowDown`, `stubTokenDenied`, `stubTokenExpired`, `stubTokenSuccess`, `stubTokenError`, `stubUser`, `stubUserUnauthorized`, `stubUserRateLimited`), both on WireMock.

**Connections UI (desktop).** Settings has a GitHub section: "Connect to GitHub" shows the short code (with "Copy code"), opens GitHub's page in the default browser and shows the address as a link in case the browser did not open, then polls `POST /api/github/connect/poll` (at the interval the backend returns, at least every second) until it says `CONNECTED`. Denied, expired or failed attempts show the backend's message and "Try again"; a missing client ID shows the readable `400` message. "Disconnect" asks for confirmation first. At the bottom of the sidebar, a Connections block shows a dot for GitHub and Hostinger from `GET /api/status` (green = connected, yellow = limited, red = error or not connected; each has a text such as "GitHub: not connected" for screen readers and as tooltip; a click opens Settings). It refreshes every 30 seconds, when the window gets focus again, and after connect or disconnect. Opening the browser goes through `window.dockline.openExternal(url)`, which the main process only allows for `https:` addresses; with `DOCKLINE_E2E_NO_BROWSER=1` (set by the end-to-end test fixture) it records the address in `globalThis.__docklineOpenedUrls` instead of opening a browser. The end-to-end tests keep their tokens in memory, so "the connection survives a restart" is not covered there; it depends on Credential Manager.

**Revoked token.** When GitHub answers `401` the dot turns red ("GitHub: error — reconnect needed") and both Settings and the sidebar offer "Reconnect". The sidebar button opens Settings and starts the device flow at once; a successful approval replaces the old token and the dot turns green. Opening Settings and focusing the window call `POST /api/github/recheck`, so a revoked token shows up without waiting for the 60-second cache. Restart persistence is covered by the backend `CredentialManagerTokenStoreTest`; to check it by hand, connect, quit Dockline, start it again and look for a green GitHub dot.

### Connecting to Hostinger

Hostinger has no "log in with Hostinger", so the user pastes an API token created in hPanel. The backend checks it with Hostinger (`GET /api/billing/v1/subscriptions`, a read-only list every account has) **before** saving it, so a bad token is never stored and never replaces a working one. The token never appears in a response, the Log section or the log file.

| Endpoint | What it does |
|---|---|
| `PUT /api/hostinger/token` with `{"token": "..."}` | Checks the token, then saves it. `200` answers the new `SourceStatus` (`CONNECTED`). Nothing is saved on: `400` blank/missing/over 1024 characters, `422` Hostinger rejected the token (401/403), `429` Hostinger's rate limit (passes on `Retry-After`), `502` Hostinger unreachable, timed out or answered 5xx. Errors are `application/problem+json` with a readable `detail`. |
| `DELETE /api/hostinger/token` | Deletes the token (`204`, also when none was saved). |
| `POST /api/hostinger/recheck` | Checks the saved token with Hostinger now and answers its `SourceStatus`. |

`GET /api/status` checks the saved Hostinger token at most once a minute (saving, deleting and recheck skip the wait): `200` = `CONNECTED`, `401`/`403` = `ERROR` (revoked or expired, replace the token; also written to the Log section), `429` or Hostinger unreachable/failing = `LIMITED`. All Hostinger calls go through `HostingerClient`. Tests fake Hostinger with `HostingerStubs` (backend) and `desktop/e2e/support/hostingerStubs.ts` (`hostingerEnv`, `stubSubscriptionsOk`, `stubUnauthorized`, `stubRateLimited`, `stubServerError`, `stubConnectionReset`).

**Hostinger in Settings (desktop).** The Hostinger section has a password-type token field (never shown again once saved; cleared after every attempt), a link to hPanel's API page (`https://hpanel.hostinger.com/api`, opened via `openExternal`), "Save" (busy while the backend checks the token), the backend's message on `400`/`422`/`429`/`502`, and "Disconnect" with a confirmation. Opening Settings calls `POST /api/hostinger/recheck`. Tests: `SettingsHostinger.test.tsx` and `e2e/hostinger-connect.spec.ts`.

**Replacing a revoked token (desktop).** When Hostinger answers 401 for the saved token, the Hostinger dot turns red ("Hostinger: error — token needs replacing") and a "Replace token" button appears next to it; it opens Settings with the token field focused. A new token is checked before it replaces the old one: an invalid one shows the message and the old state is kept. The sidebar rechecks Hostinger when the window gets focus. That the token survives a restart is not covered by the end-to-end tests (in-memory store); check by hand: save a token, quit, start again, the dot is green.

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
