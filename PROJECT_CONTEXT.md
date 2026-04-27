# Project Context

## Project Identity

This project is a flash-sale concurrency backend lab. The goal is to demonstrate progressively safer inventory deduction strategies under high concurrency.

## Main Learning Goals

- Prevent overselling under concurrent order requests.
- Compare MySQL conditional update, Redis pre-deduction, Redis Lua, and Redisson lock.
- Measure throughput, latency, success rate, and consistency.
- Document trade-offs between correctness, performance, and complexity.

## What This Project Proves

- Whether a strategy can prevent overselling.
- How Redis/Lua behaves as a pre-deduction gate.
- How Redis and MySQL can drift, and how compensation restores consistency.
- How to reset, warm, run, measure, and re-check a benchmark reproducibly.

## Non-goals

- This is not a full end-user sales product.
- Frontend work, if present, is only a lab dashboard/operator console and is not the core product.
- Buyer-facing flows, external gateway integrations, and post-order business workflows are not part of the project scope.
- No Kubernetes is required.
- No complex microservice architecture is required.

## Golden Commands

- Build:
  mvn clean package

- Test:
  mvn test

- Docker integration test:
  mvn -pl app/backend/xxxx-start -am "-Dflashsale.integration=true" test

- Run local environment:
  docker compose -f environment/docker-compose-dev.yml up -d

- Run app:
  mvn -pl app/backend/xxxx-start -am spring-boot:run

- Smoke test:
  powershell -ExecutionPolicy Bypass -File benchmark/smoke-local.ps1

- Run one benchmark:
  powershell -ExecutionPolicy Bypass -File benchmark/run-jmeter.ps1 -Strategy REDIS_LUA_WITH_COMPENSATION

## Stable Lab APIs

- POST /orders
- GET /orders/{orderNumber}
- GET /orders?userId=&yearMonth=
- GET /tickets/{ticketItemId}
- POST /admin/tickets/{ticketItemId}/stock/warmup
- POST /admin/benchmarks/reset
- GET /admin/benchmarks/consistency?ticketItemId=&yearMonth=
- GET /admin/benchmarks/runs
- GET /admin/benchmarks/runs/{runId}

## Stock Strategies

- UNSAFE_DB: intentionally unsafe baseline.
- CONDITIONAL_DB: MySQL conditional update baseline.
- REDIS_LUA: Redis pre-deduction gate without compensation.
- REDIS_LUA_WITH_COMPENSATION: Redis pre-deduction plus Redis restore on DB/order failure.

## Safe Change Rules

- Be careful when modifying stock/order/cache flows.
- Do not edit generated/vendor benchmark assets unless necessary.
- Keep benchmark results reproducible.
- Prefer small phases over large rewrites.
- Keep `benchmark/experiment-spec.json` aligned with JMeter defaults and dashboard expectations.
