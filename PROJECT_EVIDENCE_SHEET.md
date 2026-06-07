# Project Evidence Sheet

## 1. Executive project summary

| Item | Finding | Status |
|---|---|---|
| Problem | Demonstrates how flash-sale stock deduction strategies behave under concurrent load, especially oversell prevention and Redis/MySQL consistency. | VERIFIED: `README.md`, `docs/reference/REVIEWER_GUIDE.md` |
| Target users | Backend reviewers/interviewers and local operators running benchmark/reconciliation flows; not end-user ticket buyers. | VERIFIED: `README.md`, `app/frontend/README.md` |
| Main solution | Spring Boot backend lab with DB-only, Redis Lua, and compensated Redis strategies, plus benchmark reset/warmup/consistency endpoints. | VERIFIED: controllers/services under `app/backend` |
| Architecture | Five Maven modules: domain, application, infrastructure, controller, start; optional Next.js operator dashboard. | VERIFIED: `pom.xml`, `app/frontend/package.json` |
| Current status | Backend builds; unit subset passed; Docker-gated integration tests are present but skipped unless `-Dflashsale.integration=true`. | VERIFIED: command output, Surefire reports |
| Deployment | Local Docker Compose only found; no production deployment or CI/CD workflow found. | MISSING/VERIFIED |
| Likely role | Git history has 39 commits by `Thanh Quan <tranthanhquan09@gmail.com>`; local git config differs (`tranhquan099@gmail.com`). Confirm owner identity before personal attribution. | VERIFIED + MISSING |
| Strongest evidence | 2026-05-31 JMeter artifacts: 5,000 requests, 100 threads, `REDIS_LUA_WITH_COMPENSATION` at 443.03 req/s, 0 oversold, 0 Redis/DB inconsistency. | VERIFIED |
| Major gaps | No live URL, adoption metrics, production auth hardening, CI workflow, coverage percentage, or current full integration-test run. | MISSING |

## 2. Repository inspection summary

| Area inspected | Files or artifacts examined | Important findings | Evidence status | Limitations |
|---|---|---|---|---|
| Docs | `README.md`, `docs/reference/*.md`, `docs/performance/*.md` | Project is explicitly framed as a backend reliability lab. | VERIFIED | Docs are claims, cross-checked against source where possible. |
| Backend source | `app/backend/**` | 119 Java files, 5 controllers, 22 mapping annotations, 4 stock strategy implementations. | VERIFIED | Count excludes `target`. |
| Database | `TicketDetail.java`, `TicketOrderJPAMapper.java`, `OrderDeductionInfrasRepositoryImpl.java` | JPA entity for ticket item plus native monthly order tables. | VERIFIED | Dynamic monthly table is not a JPA entity. |
| Tests | `src/test/java`, Surefire XML | Unit subset passed with 23 tests; 2 integration tests are gated and skipped by default. | VERIFIED | Full Docker integration was not executed. |
| Benchmarks | `benchmark/experiment-spec.json`, `run-jmeter.ps1`, `benchmark/results/**` | Reproducible reset/warmup/JMeter/consistency artifact flow. | VERIFIED | Local machine only; no repeated statistical summary. |
| Frontend | `app/frontend/src`, lint/typecheck | Optional Next.js dashboard and backend proxy allowlist passed lint/typecheck. | VERIFIED | No browser run performed. |
| CI/CD | `.github/**` | No normal CI workflow found; only modernization hook scripts. | MISSING | GitHub remote exists but issue/PR data not inspected. |
| Git history | `git log`, `git shortlog`, `git remote -v` | 39 commits, one Git author in repo history, GitHub remote `qwan30/Flash-Sale-Concurrency-Engine`. | VERIFIED | Must confirm this author is the project owner. |

## 3. Technology verification matrix

| Technology | How it is used | Evidence reference | Depth | Resume relevance | Status |
|---|---|---|---|---|---|
| Java 21 | Maven compiler target/source. | `pom.xml` | Core | High | VERIFIED |
| Spring Boot 3.3.5 | Backend runtime, web, actuator, tests. | `pom.xml`, `application.yml` | Core | High | VERIFIED |
| Spring MVC REST | 5 controllers and order/admin/ticket endpoints. | `app/backend/xxxx-controller/...` | Core | High | VERIFIED |
| Spring Data JPA/MySQL | Ticket entity, JPQL stock updates, native order table SQL. | `TicketOrderJPAMapper.java`, `OrderDeductionInfrasRepositoryImpl.java` | Core | High | VERIFIED |
| Redis/Spring Data Redis | Stock cache, Lua atomic decrement, warmup/restore. | `RedisCacheStoreAdapter.java`, `StockOrderCacheService.java` | Core | High | VERIFIED |
| Redisson | Distributed lock config/adapter exists. | `RedissonConfig.java`, redisson package | Supporting | Medium | VERIFIED |
| Springdoc OpenAPI | Swagger UI and grouped `lab-api`. | `OpenApiConfig.java`, `application.yml` | Supporting | Medium | VERIFIED |
| Micrometer/Actuator/Prometheus | Order/reconciliation counters and health/prometheus exposure. | `OrderCreationService.java`, `OrderReconciliationService.java`, `application.yml` | Supporting | Medium | VERIFIED |
| JMeter | Benchmark plan and wrapper generate artifacts. | `benchmark/run-jmeter.ps1`, `flash-sale-order.jmx` | Core evidence | High | VERIFIED |
| Next.js/React/TypeScript | Optional operator dashboard and API proxy. | `app/frontend/package.json`, `src/lib/api.ts` | Supporting | Medium | VERIFIED |
| Docker Compose | Local MySQL/Redis plus optional observability/ELK profiles. | `environment/docker-compose-dev.yml` | Supporting | Medium | VERIFIED |
| Resilience4j | Demo hello endpoints with rate limiter/circuit breaker. | `HiController.java`, `application.yml` | Configured but minimally used | Low | VERIFIED |
| Payment/VNPAY | No source files found in working tree, but stale Surefire reports mention tests. | `rg`, Surefire XML | Unused or obsolete | Low | CONTRADICTED/MISSING |

## 4. Problem, users, and workflows

| Item | Finding | Evidence reference | Status | Confidence |
|---|---|---|---|---|
| Problem | Prevent overselling and explain Redis/MySQL consistency under flash-sale concurrency. | `README.md` | VERIFIED | High |
| Users | Backend reviewer/operator running lab flows. | `docs/reference/REVIEWER_GUIDE.md`, `app/frontend/README.md` | VERIFIED | High |
| Not users | Public buyers using a production ticket-sales platform. | `README.md` out-of-scope section | VERIFIED | High |
| Core workflow | Reset benchmark -> warm Redis -> create orders/JMeter -> consistency check. | `benchmark/experiment-spec.json` | VERIFIED | High |
| Recovery workflow | Detect Redis drift -> force reconcile -> Redis equals DB truth. | `OrderReconciliationService.java`, integration test | VERIFIED | High |
| Dashboard workflow | Proxy health/ticket/order/admin benchmark calls through allowlisted Next.js route. | `app/frontend/src/app/api/backend/[...path]/route.ts` | VERIFIED | Medium |

## 5. Verified feature and scope inventory

| Feature or capability | Implementation summary | Relevant files | Verified scope | Engineering significance | Status |
|---|---|---|---|---|---|
| Strategy-based order creation | Registry selects one of four stock deduction strategies. | `OrderStrategy.java`, `strategy/*.java`, `OrderCreationService.java` | 4 strategies | Compares unsafe, DB-safe, Redis, and compensated Redis approaches. | VERIFIED |
| Idempotent order requests | In-memory `ConcurrentHashMap` prevents same-process retry double reservation. | `IdempotencyService.java` | Local only | Prevents duplicate stock reservation during retry in lab process. | VERIFIED |
| Redis Lua stock gate | Lua script atomically checks and decrements stock. | `RedisCacheStoreAdapter.java` | 1 Lua operation | Handles high-concurrency pre-deduction without race in Redis. | VERIFIED |
| Redis compensation | Restores Redis when DB decrement/order write fails after Redis decrement. | `RedisLuaCompensatingStockDeductionStrategy.java`, `OrderCreationService.java` | Compensated strategy | Addresses cross-store failure path. | VERIFIED |
| Consistency/reconciliation | Compares Redis, DB stock, orders, drift; scheduled and manual repair. | `ConsistencyCheckService.java`, `OrderReconciliationService.java`, `AdminBenchmarkController.java` | Scheduled + endpoint | Makes eventual repair measurable. | VERIFIED |
| Benchmark artifact ingestion | Reads saved run manifests and prevents path traversal. | `BenchmarkRunService.java`, test | Runs list/detail | Supports dashboard/report review from saved artifacts. | VERIFIED |
| OpenAPI docs | Springdoc UI and grouped controller package scan. | `OpenApiConfig.java`, `application.yml` | `/swagger-ui.html`, `/v3/api-docs/lab-api` | Improves reviewer/API inspectability. | VERIFIED |
| Operator dashboard | Next.js pages consume health/ticket/order/benchmark APIs via proxy. | `app/frontend/src/lib/api.ts`, components | Supporting UI | Helps inspect backend lab state. | VERIFIED |

## 6. Architecture and design decisions

| Technical problem | Implemented solution | Evidence reference | Trade-off | Engineering significance | Status |
|---|---|---|---|---|---|
| Oversell under concurrency | Conditional DB update and Redis Lua strategies. | `TicketOrderJPAMapper.java`, `RedisCacheStoreAdapter.java` | DB approach is slower; Redis adds consistency complexity. | Shows real concurrency trade-offs. | VERIFIED |
| Cross-store inconsistency | Compensation plus reconciliation to DB truth. | `OrderCreationService.java`, `OrderReconciliationService.java` | Reconciliation is lab-scoped to default item unless invoked manually. | Demonstrates failure recovery design. | VERIFIED |
| Strategy comparison | `StockDeductionStrategy` interface and registry. | `StockDeductionStrategy.java`, registry | More classes than one hardcoded flow. | Makes benchmark comparisons clean. | VERIFIED |
| Repeatable benchmark setup | Reset/warmup/JMeter/consistency wrapper. | `benchmark/run-jmeter.ps1` | Requires local services and JMeter. | Converts performance claims into artifacts. | VERIFIED |
| API inspectability | Springdoc OpenAPI config. | `OpenApiConfig.java` | Runtime docs require backend running. | Useful for reviewers. | VERIFIED |
| Browser CORS avoidance | Next.js proxy with route allowlist. | `route.ts` | Dashboard only exposes selected backend routes. | Keeps frontend integration simple. | VERIFIED |

## 7. Personal contribution analysis

| Contribution | Evidence of ownership | Relevant commits or files | Other contributors involved | Attribution confidence | Status |
|---|---|---|---|---|---|
| Overall Git-tracked repo work | `git shortlog -sne --all` shows 39 commits by `Thanh Quan <tranthanhquan09@gmail.com>`. | Full history | None in Git history | Medium until owner confirms identity | VERIFIED/MISSING |
| Concurrency/reconciliation work | Commits `f4e8342`, `9706c3e`, `0ef18cf`, `a2b06b7`. | Strategy/order services | None in Git history | Medium | VERIFIED |
| Benchmark evidence | Commit `62dcb3d`; benchmark artifacts under `benchmark/results`. | Result folders | None in Git history | Medium | VERIFIED |
| Docs/OpenAPI refresh | Commits `652ddb8`, `58e7ee5`, docs files. | `OpenApiConfig.java`, docs | None in Git history | Medium | VERIFIED |
| Potential scaffold/copied code | Some controller model comments list `@author vantrang`; generated/vendor artifacts exist. | `ResultMessage.java`, `ResultUtil.java` | Unknown | Low | INFERRED |

## 8. Technical challenges

| Challenge | Why it was difficult | Solution implemented | Evidence | Result | Status |
|---|---|---|---|---|---|
| Preventing oversell | Concurrent requests can pass naive stock checks. | Conditional DB update and Redis Lua atomic decrement. | `TicketOrderJPAMapper.java`, `RedisCacheStoreAdapter.java` | Safe strategies show 0 oversold in benchmark artifacts. | VERIFIED |
| Redis/DB drift | Redis decrement and DB/order insert are not one transaction. | Compensation and reconciliation repair Redis to DB truth. | `OrderCreationService.java`, `OrderReconciliationService.java` | 0 drift in compensated benchmark run. | VERIFIED |
| Repeatable performance evidence | Load-test claims are unsafe without reset/workload/artifacts. | PowerShell wrapper saves reset, warmup, JTL, HTML, consistency, run manifest. | `run-jmeter.ps1`, `benchmark/results` | Resume-safe local benchmark values with qualification. | VERIFIED |
| Idempotent retries | Retried requests can reserve stock twice. | In-memory key to response cache. | `IdempotencyService.java` | Same-process only; not distributed/durable. | VERIFIED with qualification |
| Integration testing stores | Needs MySQL/Redis containers. | Testcontainers-gated integration tests. | `FlashSaleConcurrencyIntegrationTest.java` | Present but skipped unless property enabled. | VERIFIED |

## 9. Existing measurable evidence

| Category | Metric | Baseline | Final value | Change | Measurement method | Evidence | Resume-safe? |
|---|---|---|---|---|---|---|---|
| Performance | Throughput, 5,000 requests/100 threads | `UNSAFE_DB`: 84.71 req/s on 2026-05-31 | `REDIS_LUA_WITH_COMPENSATION`: 443.03 req/s | 5.23x vs unsafe local baseline | JMeter wrapper on ACER | `benchmark/results/*20260531*/run.json` | Yes, with qualification |
| Performance | Average latency | `UNSAFE_DB`: 1084.86 ms | compensated Redis: 165.95 ms | -918.91 ms local | JMeter wrapper | same | Yes, with qualification |
| Reliability | Oversold count | `UNSAFE_DB`: 4000 | compensated Redis: 0 | eliminated oversell in local run | Consistency endpoint after JMeter | `consistency.json` | Yes, with qualification |
| Consistency | Redis/DB inconsistency | `UNSAFE_DB`: 1 | compensated Redis: 0 | resolved in local run | Consistency endpoint | `run.json` | Yes, with qualification |
| Testing | Backend unit tests | n/a | 23 passed, 0 failures/errors | n/a | `mvn -q -pl app/backend/xxxx-application,app/backend/xxxx-controller,app/backend/xxxx-infrastructure -am test` | Surefire XML | Yes |
| Frontend quality | Lint/typecheck | n/a | both passed | n/a | `npm run lint`, `npm run typecheck` | command output | Supporting |
| Build | Backend package | n/a | succeeded | n/a | `mvn -q -pl app/backend/xxxx-start -am -DskipTests package` | command output | Supporting |
| Adoption | Users/stars/traffic | MISSING | MISSING | MISSING | Not found | n/a | No |

## 10. Engineering scope counts

| Scope item | Verified count | Counting method | Exclusions | Evidence | Resume relevance |
|---|---:|---|---|---|---|
| Java source files | 119 | Recursive `*.java` under `app/backend`, excluding `target` | Generated build output | command output | Medium |
| Maven backend modules | 5 | Root `pom.xml` modules | Frontend/docs | `pom.xml` | High |
| REST controllers | 5 | `@RestController` count | Tests | command output | Medium |
| Mapping annotations | 22 | `@(Get/Post/.../Request)Mapping` scan | Actuator auto endpoints | command output | Medium |
| Core strategy implementations | 4 | `implements StockDeductionStrategy` | Registry/interface | command output | High |
| JPA entities | 2 | `@Entity` scan | Dynamic monthly order table | command output | Medium |
| Test methods in source | 12 | `@Test` scan in backend source | Stale target-only tests | command output | Medium |
| Tests passed in current unit subset | 23 | Surefire XML after command | Start integration skipped | Surefire XML | High |
| Benchmark result folders with summary rows | 10 | `summary-row.md` scan | HTML/vendor contents | command output | High |
| Frontend source files | 40 | `app/frontend/src` file extension count | `node_modules`, `.next` | command output | Low/Medium |

## 11. Quality, reliability, and security evidence

| Area | Control or practice | Evidence reference | Validation performed | Limitation | Status |
|---|---|---|---|---|---|
| Validation | Create order request validates ticket/user/quantity/strategy/idempotency key. | `OrderCreationService.java` | Unit tests include invalid request. | Manual validation, no Bean Validation annotations. | VERIFIED |
| Transaction handling | DB work is `@Transactional`; rollback marked on exception. | `OrderCreationService.java` | Unit tests passed. | Redis outside DB transaction. | VERIFIED |
| Compensation | Redis restore on selected failure paths. | `OrderCreationService.java`, compensated strategy | Benchmark and integration test artifacts. | Double-fault handled by reconciliation. | VERIFIED |
| Integration tests | Testcontainers MySQL/Redis tests for concurrency and reconciliation. | `FlashSaleConcurrencyIntegrationTest.java` | Skipped by default in current run. | Needs Docker and `-Dflashsale.integration=true`. | VERIFIED |
| Observability | Micrometer counters, actuator health/prometheus, Prometheus compose. | `application.yml`, services | Build passed. | No monitoring screenshots/alerts verified. | VERIFIED |
| Auth/security | Admin endpoints documented as local, not app-auth protected. | `docs/reference/API_REFERENCE.md`, source has no auth config | Static scan | Not production-secure. | VERIFIED |
| Secret management | Uses env vars with local defaults for DB/Redis. | `application.yml`, compose | Static scan | Defaults are dev-only; compose has local passwords. | VERIFIED with limitation |
| Frontend proxy | Route allowlist and hop-by-hop header removal. | `route.ts` | `npm run lint`, `typecheck` passed | Not a full auth boundary. | VERIFIED |

## 12. Delivery and deployment evidence

| Area | Implementation | Evidence | Automation level | Measurable outcome | Status |
|---|---|---|---|---|---|
| Local dependencies | Docker Compose for MySQL/Redis; optional observability/ELK profiles. | `environment/docker-compose-dev.yml` | Manual local | Services defined, not started in audit. | VERIFIED |
| Backend build | Maven package for `xxxx-start`. | command output | Manual | Build succeeded. | VERIFIED |
| Frontend checks | npm lint/typecheck scripts. | `package.json`, command output | Manual | Both passed. | VERIFIED |
| Benchmark run | PowerShell wrapper around JMeter. | `run-jmeter.ps1` | Semi-automated local | Saves reproducible artifacts. | VERIFIED |
| CI/CD | No normal pipeline workflow found. | `.github` listing | None found | MISSING | MISSING |
| Production deployment | No live URL/config found. | repo scan | None found | MISSING | MISSING |

## 13. Adoption and external-impact evidence

| Metric | Value | Observation period | Source | Reliability | Resume-safe? |
|---|---|---|---|---|---|
| Active users | Not found | n/a | Repo inspection | n/a | No |
| GitHub stars/forks | Not inspected live | n/a | Remote only: `https://github.com/qwan30/Flash-Sale-Concurrency-Engine.git` | MISSING | No |
| Production traffic | Not found | n/a | Repo inspection | n/a | No |
| Real transactions | Not found; benchmark/test data only | n/a | benchmark artifacts | High for lab only | No as adoption |

## 14. Claims that are currently resume-safe

| Claim | Supporting evidence | Recommended emphasis | Required qualification |
|---|---|---|---|
| Built a Spring Boot backend lab comparing unsafe DB, conditional DB, Redis Lua, and compensated Redis stock-deduction strategies. | `OrderStrategy.java`, `strategy/*.java`, benchmark artifacts | Backend concurrency design | Confirm Git author identity before "I". |
| Implemented Redis Lua pre-deduction plus compensation/reconciliation for Redis/MySQL stock consistency. | `RedisCacheStoreAdapter.java`, `OrderCreationService.java`, `OrderReconciliationService.java` | Reliability under failure paths | Lab scope, not distributed transaction system. |
| Produced reproducible JMeter benchmark evidence for 5,000 requests at 100 threads. | `experiment-spec.json`, `run-jmeter.ps1`, `benchmark/results/*20260531*/run.json` | Measurement discipline | Local ACER machine; rerun before broad performance claim. |
| Demonstrated local compensated Redis run with 443.03 req/s, 0 oversold, 0 Redis/DB inconsistency. | `REDIS_LUA_WITH_COMPENSATION-20260531-185527/run.json` | Strongest metric | Local benchmark only, single recorded run. |
| Added local API observability via OpenAPI, health, prometheus endpoint, and saved benchmark run APIs. | `OpenApiConfig.java`, `application.yml`, `BenchmarkRunService.java` | Operability/reviewer experience | Not production monitoring. |
| Verified current backend unit subset and frontend static checks. | Commands run during audit | Quality evidence | Integration tests not executed in this audit. |

## 15. Claims that must not be used yet

| Potential claim | Why unsafe or weak | Missing evidence | How to validate |
|---|---|---|---|
| Production-ready ticketing platform | Docs explicitly say not a full product and admin endpoints lack app auth. | Production auth, deployment, user workflows | Build full product scope and security review. |
| Payment-ready booking product | Payment is documented as out of scope; no payment source files found. | Real payment flow/source/tests | Implement and verify payment integration. |
| Microservices/Kubernetes platform | Explicitly out of scope. | Services, deployment manifests, orchestration | Add actual deployment architecture. |
| Reduced latency by X% in production | Metrics are local benchmark, not production. | Production baseline/final, environment, repeated runs | Run controlled benchmarks or production telemetry. |
| Secured admin APIs | Admin endpoints are not app-auth protected. | Authz/authn controls, security tests | Add auth and test access controls. |
| Real user adoption | No traffic/user data found. | Analytics, accounts, usage logs | Provide verifiable adoption source. |

## 16. Missing evidence and recommended measurements

| Priority | Missing metric/evidence | Why it matters | Recommended tool | Exact procedure or command | Expected output | Risk |
|---|---|---|---|---|---|---|
| P1 | Owner identity | Required for personal attribution. | Git/user confirmation | Confirm whether `Thanh Quan <tranthanhquan09@gmail.com>` is you. | Attribution confidence High/Low | Low |
| P1 | Full Docker integration run | Proves concurrency/reconciliation tests current. | Maven/Testcontainers | `mvn -pl app/backend/xxxx-start -am "-Dflashsale.integration=true" test` | 2 integration tests pass | Medium: Docker required |
| P1 | Repeated benchmark confidence | Makes performance claims stronger. | JMeter wrapper | Run each strategy 3-5 times with same fixture; summarize median/p95. | Stable benchmark table | Medium local load |
| P2 | Coverage percentage | Resume-safe quality metric. | JaCoCo | Add/use coverage plugin, run unit tests. | Coverage report | Low |
| P2 | CI evidence | Shows automated delivery. | GitHub Actions | Add workflow for Maven test/package and frontend lint/typecheck/build. | Passing CI badge/log | Low |
| P2 | Security posture | Avoids misleading "secure" claims. | Static/security review | Add auth for admin routes or document local-only boundary in deployment. | Security checklist/tests | Medium |
| P3 | Live demo URL | Helps resume context. | Deployment platform | Deploy safe demo without public admin reset, or keep local-only. | URL and deployment logs | Medium |
| P3 | Adoption | Business impact evidence. | GitHub/analytics | Inspect stars/forks/traffic or user feedback if public. | External metric | Low |

## 17. Questions for me

### Critical questions

1. Are you `Thanh Quan <tranthanhquan09@gmail.com>` or another identity tied to these commits? This determines whether personal-contribution confidence can be High.
2. Was this a solo project or did parts come from a scaffold/class/team member? This prevents over-attribution.
3. Is there a live deployment URL, or should the project be framed as local-only? This affects deployment claims.

### Valuable questions

1. What dates should be used as the real development period? Git history shows 2026-04-27 through 2026-05-31.
2. Can you rerun the full integration test and benchmark matrix on the target machine? This would strengthen metric quality.
3. Should resume bullets mention the optional dashboard, or keep bullets backend-only?

### Optional questions

1. Are any GitHub stars/forks/issues/user feedback available?
2. Do you want to add CI before using this in applications?
3. Do you want to remove or qualify stale learning-doc claims about payment/microservices?

## 18. Evidence quality score

| Category | Score | Explanation |
|---|---:|---|
| Project context | 4 | Clear docs and source agree on lab scope. |
| Personal ownership | 3 | Git history has one author, but user identity not confirmed and some source comments suggest older provenance. |
| Technical complexity | 4 | Real concurrency, Redis Lua, compensation, reconciliation, benchmark evidence. |
| Functional scope | 3 | Focused backend lab with meaningful APIs; intentionally not full product. |
| Performance | 4 | Strong local benchmark artifacts, but single-machine and limited repeated-run statistics. |
| Testing and reliability | 3 | Unit subset passed; integration tests exist but were skipped by default. |
| Security | 2 | Local boundary is documented; no production auth for admin controls. |
| Deployment and automation | 2 | Local Docker and scripts exist; no CI/CD or live deployment found. |
| Adoption or external impact | 0 | No adoption evidence found. |
| Overall evidence quality | 3 | Strong backend technical proof, limited external/deployment/ownership confirmation. |

## 19. Handoff package for the Resume Bullet Writer

### Project identity

* Project name: Flash-Sale Concurrency Backend Lab / Flash-Sale-Concurrency-Engine
* Project type: Backend reliability/concurrency lab
* Target role: Backend Engineer
* My role: MISSING until owner confirms Git author identity
* Team size: INFERRED solo from Git history, not confirmed
* Development period: VERIFIED Git history spans 2026-04-27 to 2026-05-31
* Live URL: MISSING
* Repository URL: `https://github.com/qwan30/Flash-Sale-Concurrency-Engine.git`

### Problem and solution

* Problem: Naive stock deduction can oversell under flash-sale concurrency and Redis/DB state can drift.
* Users: Backend reviewers/interviewers and local operators.
* Solution: Spring Boot lab comparing four stock strategies with JMeter evidence, consistency checks, and reconciliation.

### Strongest verified technical contributions

* Strategy-based stock deduction with `UNSAFE_DB`, `CONDITIONAL_DB`, `REDIS_LUA`, `REDIS_LUA_WITH_COMPENSATION`.
* Redis Lua atomic stock gate.
* Redis compensation and scheduled/manual reconciliation to DB truth.
* Reproducible benchmark wrapper and saved result artifacts.
* OpenAPI/admin benchmark APIs and optional operator dashboard.

### Strongest verified metrics

* 5,000 requests, 100 threads benchmark contract.
* `REDIS_LUA_WITH_COMPENSATION`: 443.03 req/s, avg 165.95 ms, p95 492 ms, p99 715 ms.
* Safe compensated run: 1,000 accepted, 4,000 rejected, 0 oversold, 0 Redis/DB inconsistency.
* Unsafe DB run: 5,000 accepted against 1,000 stock, 4,000 oversold.
* Current audit: 23 backend unit tests passed; frontend lint/typecheck passed; backend package build passed.

### Important technologies with demonstrated usage

* Java 21/Spring Boot: backend runtime and modules.
* Spring Data JPA/MySQL: stock updates and monthly order tables.
* Redis/Spring Data Redis: stock cache and Lua decrement.
* JMeter: benchmark measurement and artifacts.
* Next.js/TypeScript: optional operator dashboard and API proxy.

### Strongest technical challenges

* Challenge: concurrent oversell. Solution: conditional DB update and Redis Lua gate. Result: safe benchmark runs show 0 oversold.
* Challenge: Redis/DB drift. Solution: compensation and reconciliation. Result: compensated run shows 0 inconsistency.
* Challenge: reproducible performance claims. Solution: reset/warmup/JMeter/consistency artifact pipeline. Result: local metrics are resume-safe with qualification.

### Resume-safe scope

* Verified scope item: backend REST API mappings. Verified count: 22 mapping annotations. Evidence: source scan.
* Verified scope item: stock strategies. Verified count: 4. Evidence: `OrderStrategy.java`, strategy implementations.
* Verified scope item: benchmark runs. Verified count: 10 summary rows. Evidence: `benchmark/results/**/summary-row.md`.
* Verified scope item: passing current checks. Verified count: 23 backend unit tests plus frontend lint/typecheck and backend package build. Evidence: command outputs/Surefire XML.

### Missing information

* Missing item: owner identity. Why it matters: personal attribution. Next action: confirm Git author.
* Missing item: live URL. Why it matters: deployment claim. Next action: provide or mark local-only.
* Missing item: repeated benchmark statistics. Why it matters: stronger performance claim. Next action: rerun matrix 3-5 times.

### Warnings for the Resume Bullet Writer

* Do not invent user counts, revenue, production traffic, uptime, or adoption.
* Qualify all benchmark metrics as local ACER-machine evidence unless rerun elsewhere.
* Do not call this a production ticketing/e-commerce/payment platform.
* Do not claim secured admin APIs; source/docs say admin endpoints are local controls without product-grade auth.
* Do not attribute all work to the user until Git author identity is confirmed.
* Treat payment/microservice claims in learning docs as aspirational/out of scope unless source is added.

## Final quality-control checklist

* Strong claims include file, command, or artifact references.
* Project capabilities are not automatically attributed personally.
* No adoption, production, or business metrics were invented.
* Benchmark baseline/final values include method and local environment.
* Generated/vendor/build output was excluded from scope counts where practical.
* Technologies were verified through implementation usage.
* Missing evidence and safe measurement plans are identified.
* No polished resume bullets are included.
