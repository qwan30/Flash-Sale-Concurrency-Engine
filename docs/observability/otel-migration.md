# OpenTelemetry Migration Guide

This document describes how to run the application with OpenTelemetry enabled and how to migrate from the default Micrometer/Prometheus stack.

## Running the Observability Stack with OTel

To launch the observability stack (including Jaeger for traces, Prometheus for metrics, Grafana, and the OpenTelemetry Collector):

```powershell
# Run the core platform with the otel profile
docker compose -f environment/docker-compose-dev.yml -f environment/docker-compose-otel.yml --profile observability --profile otel up -d
```

### Accessing the UIs
- **Grafana**: http://localhost:3000 (admin/admin)
- **Jaeger**: http://localhost:16686 (traces)
- **Prometheus**: http://localhost:9090 (metrics)

## Spring Boot Configuration

To start the backend with OpenTelemetry enabled, ensure the `otel` Spring profile is active. This replaces Brave with OpenTelemetry for tracing.

```powershell
mvn.cmd spring-boot:run -pl app/backend/xxxx-start -Dspring-boot.run.profiles=dev,otel
```

## Telemetry Flow
1. The backend app pushes OTLP traces and metrics to the **OTel Collector** on port `4317`.
2. The OTel Collector exports traces to **Jaeger** and exposes a `/metrics` endpoint for **Prometheus** to scrape.
3. **Grafana** connects to Jaeger and Prometheus to visualize both.

---

# Optional OTel/k6 evidence lane

The default application remains on its existing Brave bridge and Micrometer/Prometheus metrics.
The files in this lane are reversible, default-off evidence tooling:

- `environment/otel/otel-collector.yml` receives OTLP traces and metrics and exposes Prometheus metrics.
- `environment/docker-compose-otel.yml` adds only the collector under the `otel` profile.
- `application-otel.yml` documents the opt-in endpoint; it does not add an OTel runtime dependency.
- `benchmark/flash-sale-reservation-k6.js` and `benchmark/run-reservation-k6.ps1` mark traffic synthetic,
  propagate `traceparent`, and preserve the reservation API's bounded status contract.

Before switching runtime tracing, add one bridge only, verify metric-name parity with the Micrometer
dashboard, and run the same invariant and convergence assertions as the JMeter lane. A successful k6
run alone is not a release or recovery certification.

## Pinned reference audit - 2026-08-11

The local `reference/opentelemetry-demo` checkout was re-read at pinned SHA
`c983708e6e308f8395a6c9ce8ddb89705f910c1f`; `git status --short` was clean. The audit is based on
these source locations, not on a running reference stack:

- `CHANGELOG.md` records the Locust-to-k6 migration and the `xk6-otel` extension, plus the
  `loadGeneratorTraffic` and `loadGeneratorVUs` feature flags.
- `src/load-generator/README.md` and `src/load-generator/script.js` define the k6 scenario boundary,
  the opt-in browser VU, `k6/x/otel`, `traceparent` injection and synthetic-request baggage.
- `src/load-generator/entrypoint.sh` polls flagd and restarts k6 only when the VU flag changes;
  `compose.yaml` wires OTLP HTTP/protobuf export and the `k6.` metric prefix.
- `telemetry-schema/attributes/request.yaml` defines the typed `demo.synthetic_request` attribute
  and documents its baggage propagation.

### Adaptation decisions

1. Keep this project's existing Brave bridge and Micrometer metric names as the default; the OTel
   collector and k6 runner remain a reversible, default-off lane.
2. Port only the bounded ideas already represented in this checkout: typed default-off fault/synthetic
   markers, `traceparent` propagation, schema-first telemetry, route/span normalization and bounded
   cardinality. Do not copy the reference demo's feature-flag service, browser traffic, or product
   workflow into the reservation domain.
3. Keep correctness evidence outside the telemetry transport: the k6 lane must reuse the reservation
   invariant, terminal-coverage and convergence checks before any runtime default changes.

This closes the pinned-source audit only. No OTel/k6 runtime, metric-parity, Docker, or browser claim is
made here; those remain separate Phase 16 and final-certification gates.
