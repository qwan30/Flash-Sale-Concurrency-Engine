# Project Context

## Project Identity

This project is a flash-sale ticketing backend lab. The goal is to demonstrate progressively safer inventory deduction strategies under high concurrency.

## Main Learning Goals

- Prevent overselling under concurrent order requests.
- Compare MySQL conditional update, Redis pre-deduction, Redis Lua, and Redisson lock.
- Measure throughput, latency, success rate, and consistency.
- Document trade-offs between correctness, performance, and complexity.

## Non-goals

- This is not a full end-user ticketing product.
- No frontend UI is required.
- No payment integration is required.
- No Kubernetes is required.
- No complex microservice architecture is required.

## Golden Commands

- Build:
  mvn clean package

- Test:
  mvn test

- Run local environment:
  docker compose -f environment/docker-compose-dev.yml up -d

- Run app:
  mvn spring-boot:run -pl xxxx-start

- Smoke test:
  powershell -ExecutionPolicy Bypass -File benchmark/smoke-local.ps1

## Stable Lab APIs

- POST /orders
- GET /orders/{orderNumber}
- GET /orders?userId=&yearMonth=
- GET /tickets/{ticketItemId}
- POST /admin/tickets/{ticketItemId}/stock/warmup
- POST /admin/benchmarks/reset
- GET /admin/benchmarks/consistency?ticketItemId=&yearMonth=

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
