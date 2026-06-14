# Environment Variables Reference

> Complete reference. Source: `application.yml`, `.env.example`, `docker-compose.prod.yml`.

## MySQL

| Variable | Default | Used In |
|---|---|---|
| `MYSQL_URL` | `jdbc:mysql://localhost:3316/vetautet` | `application.yml` |
| `MYSQL_USER` | `root` | `application.yml` |
| `MYSQL_PASSWORD` | `root1234` | `application.yml` |
| `MYSQL_ROOT_PASSWORD` | `root1234` | `docker-compose.prod.yml` |
| `MYSQL_DATABASE` | `vetautet` | `docker-compose.prod.yml` |
| `MYSQL_PORT` | `3316` | `docker-compose.prod.yml` |

## Redis

| Variable | Default | Used In |
|---|---|---|
| `REDIS_HOST` | `127.0.0.1` | `application.yml` |
| `REDIS_PORT` | `6319` | `application.yml`, docker-compose |
| `REDIS_PASSWORD` | (empty) | `application.yml`, Redisson |
| `REDISSON_MODE` | `single` | Redisson config (`single` / `sentinel`) |
| `REDIS_SENTINEL_MASTER` | `mymaster` | Redisson sentinel |
| `REDIS_SENTINEL_NODES` | `redis://localhost:26379,redis://localhost:26380,redis://localhost:26381` | Redisson sentinel |

## Kafka

| Variable | Default | Used In |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | `application.yml` |
| `KAFKA_TOPIC` | `flashsale.orders` | `application.yml` |
| `KAFKA_PORT` | `9094` | `docker-compose.prod.yml` |

## Application

| Variable | Default | Used In |
|---|---|---|
| `BENCHMARK_RESULTS_DIR` | `benchmark/results` | `application.yml` |
| `BACKEND_PORT` | `1122` | `docker-compose.prod.yml` |
| `FRONTEND_PORT` | `80` | `docker-compose.prod.yml` |

## Frontend

| Variable | Default | Used In |
|---|---|---|
| `BACKEND_BASE_URL` | `http://localhost:1122` | `.env.local`, Next.js proxy |

## Port Map

| Service | Dev | Prod |
|---|---|---|
| Backend | `1122` | `1122` (via `BACKEND_PORT`) |
| Frontend | `3000` (dev) | `80` (via `FRONTEND_PORT`) |
| MySQL | `3316` | `3316` (via `MYSQL_PORT`) |
| Redis | `6319` | `6319` (via `REDIS_PORT`) |
| Kafka | `9094` | `9094` (via `KAFKA_PORT`) |
| Prometheus | `9090` (observability) | — |
| Grafana | `3000` (observability) | — |

## .env.example

```ini
MYSQL_URL=jdbc:mysql://localhost:3316/vetautet
MYSQL_USER=root
MYSQL_PASSWORD=root1234
REDIS_HOST=127.0.0.1
REDIS_PORT=6319
REDIS_PASSWORD=
REDISSON_MODE=single
REDIS_SENTINEL_MASTER=mymaster
REDIS_SENTINEL_NODES=redis://localhost:26379,redis://localhost:26380,redis://localhost:26381
KAFKA_BOOTSTRAP_SERVERS=localhost:9094
KAFKA_TOPIC=flashsale.orders
```
