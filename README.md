# Dockline

A Windows desktop app that brings GitHub and Hostinger together in one window.

Planning lives in GitHub Issues — start with the PRD issue.

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
