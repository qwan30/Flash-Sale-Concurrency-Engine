# Docker Setup

> Docker Compose configurations for dev, observability, ELK, and production.

## Development (`environment/docker-compose-dev.yml`)

```bash
docker compose -f environment/docker-compose-dev.yml up -d
```

### Default Services

| Service | Image | Port |
|---|---|---|
| MySQL | `mysql:8.0` | `3316:3306` |
| Redis | `redis:latest` | `6319:6379` |
| Kafka | `apache/kafka:3.9.0` (KRaft) | `9094:9092` |

### Optional Profiles

```bash
# Observability (Prometheus, Grafana, exporters)
docker compose -f environment/docker-compose-dev.yml --profile observability up -d

# ELK (Elasticsearch, Logstash, Kibana)
docker compose -f environment/docker-compose-dev.yml --profile elk up -d
```

### Observability Services

| Service | Image | Port |
|---|---|---|
| Prometheus | `prom/prometheus:latest` | `9090` |
| Grafana | `grafana/grafana` | `3000` |
| Node Exporter | `prom/node-exporter:latest` | `9100` |
| MySQL Exporter | `prom/mysqld-exporter` | `9104` |
| Redis Exporter | `oliver006/redis_exporter` | `9121` |

### ELK Services

| Service | Image | Port |
|---|---|---|
| Elasticsearch | `elasticsearch:7.17.25` | `9200`, `9300` |
| Logstash | `logstash:7.17.25` | `5044`, `9600` |
| Kibana | `kibana:7.17.25` | `5601` |

## Production (`environment/docker-compose.prod.yml`)

Uses pre-built GHCR images from CD pipeline:

```bash
docker compose -f environment/docker-compose.prod.yml up -d
```

| Service | Image | Port |
|---|---|---|
| MySQL | `mysql:8.0` | `${MYSQL_PORT:-3316}:3306` |
| Redis | `redis:7-alpine` | `${REDIS_PORT:-6319}:6379` |
| Kafka | `apache/kafka:3.9.0` | `${KAFKA_PORT:-9094}:9092` |
| Backend | `ghcr.io/qwan30/flashsale-backend:latest` | `${BACKEND_PORT:-1122}:1122` |
| Frontend | `ghcr.io/qwan30/flashsale-frontend:latest` | `${FRONTEND_PORT:-80}:80` |

### Health Checks

| Service | Check | Interval |
|---|---|---|
| MySQL | `mysqladmin ping` | 10s |
| Redis | `redis-cli ping` | 10s |
| Backend | depends on MySQL + Redis healthy | — |

### Network

All production services share `flashsale-network` (bridge). Internal DNS: `mysql`, `redis`, `kafka`, `backend`, `frontend`.

## Nginx (`environment/docker-compose-nginx.yml`)

Reverse proxy for multi-instance backend setups.

## Build Locally

```bash
docker build -t flashsale-backend -f app/backend/Dockerfile .
docker build -t flashsale-frontend app/frontend
```
