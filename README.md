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
