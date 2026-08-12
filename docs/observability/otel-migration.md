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
