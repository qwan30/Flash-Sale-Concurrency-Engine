# CI/CD Pipeline Reference

> Source: `.github/workflows/ci.yml`, `.github/workflows/cd.yml`

## CI Pipeline

**Trigger**: Push and PR to `master`. **6 jobs**, runs on `ubuntu-latest`.

| Job | Depends On | What It Does |
|---|---|---|
| Backend — Unit Tests | — | Java 21, `mvn test` for application/controller/infrastructure modules |
| Backend — Integration Tests | unit tests | Docker-gated Testcontainers, `-Dflashsale.integration=true` |
| Backend — Package JAR | unit tests | `mvn -DskipTests package`, uploads fat JAR artifact |
| Observability Smoke Test | JAR build | Starts backend JAR, verifies health/Prometheus/structured logs |
| Frontend — Lint, Typecheck & Build | — | Node 20, `npm ci`, `lint` → `typecheck` → `build` |
| Infra — Config Validation | — | Validates docker-compose, Prometheus, ELK, Nginx, Redis Sentinel configs |

### Smoke Test Details
- Starts MySQL + Redis + Kafka via Docker Compose
- Waits up to 90s for backend health
- Verifies: `GET /actuator/health`, Prometheus JVM metrics, pipe-delimited log format
- Dumps logs to `GITHUB_STEP_SUMMARY` on failure

### Artifacts Uploaded
- `surefire-reports` (unit tests on failure)
- `integration-surefire-reports` (integration tests, always)
- `maven-integration-log` (integration tests, always)
- `backend-jar` (JAR build)
- `smoke-test-startup-logs` (smoke test on failure)

## CD Pipeline

**Trigger**: Push to `master`. Builds and pushes Docker images to GHCR.

| Job | Image | Dockerfile |
|---|---|---|
| Backend — Docker Build & Push | `ghcr.io/qwan30/flashsale-backend` | `app/backend/Dockerfile` |
| Frontend — Docker Build & Push | `ghcr.io/qwan30/flashsale-frontend` | `app/frontend/Dockerfile` |

Each image tagged `latest` + `${{ github.sha }}`. Uses Docker Buildx with GHA cache backend.

## Required Secrets

| Secret | Purpose |
|---|---|
| `GITHUB_TOKEN` | GHCR authentication (auto-provided by GitHub Actions) |

## Cache Strategy

| What | How |
|---|---|
| Maven deps | `setup-java` with `cache: maven` |
| Testcontainers images | `actions/cache@v4` keyed by `**/pom.xml` hash |
| Docker layers | `type=gha` (GitHub Actions cache) |
| npm deps | `setup-node` with `cache: npm` |

## Local Equivalents

```bash
# Unit tests
mvn -pl app/backend/xxxx-application,app/backend/xxxx-controller,app/backend/xxxx-infrastructure -am test

# Integration tests
mvn -pl app/backend/xxxx-start -am -Dflashsale.integration=true test

# Build
mvn -pl app/backend/xxxx-start -am -DskipTests package

# Frontend checks
cd app/frontend && npm run lint && npm run typecheck && npm run build

# Infra validation
docker compose -f environment/docker-compose-dev.yml config --quiet
```
