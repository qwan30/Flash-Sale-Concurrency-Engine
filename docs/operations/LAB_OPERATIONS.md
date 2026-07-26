# Lab Operations

How to run the project locally for development, testing, and benchmarking.

## Prerequisites

- Java 21, Maven 3.9+, Node.js 22+, Docker with Compose

## Run the Full Stack

### 1. Infrastructure

```bash
docker compose -f environment/docker-compose-dev.yml up -d mysql redis kafka
```

Uses Confluent Kafka 7.9.0 for dev (Apache Kafka 3.9.0's KRaft listener validation rejects
`localhost` in `advertised.listeners`, which breaks host-side backend access).

### 2. Backend (Spring Boot, port 1122)

```bash
mvn -pl app/backend/xxxx-start -am -DskipTests spring-boot:run
```

Alternatively, package and run the JAR:

```bash
mvn -pl app/backend/xxxx-start -am -DskipTests package
java -jar app/backend/xxxx-start/target/xxxx-start-1.0-SNAPSHOT.jar
```

### 3. Frontend (Next.js, port 3000)

```bash
cd app/frontend
cp .env.local.example .env.local   # first time only
npm install                         # first time only
npm run dev
```

## Verify

| What | URL |
|------|-----|
| Frontend | http://localhost:3000 |
| Backend health | http://localhost:1122/actuator/health |
| Swagger UI | http://localhost:1122/swagger-ui.html |
| OpenAPI | http://localhost:1122/v3/api-docs |

## Run Benchmarks

```powershell
powershell -ExecutionPolicy Bypass -File benchmark/smoke-local.ps1
powershell -ExecutionPolicy Bypass -File benchmark/run-jmeter.ps1 -Strategy REDIS_LUA_WITH_COMPENSATION
```

See [BENCHMARKING.md](../performance/BENCHMARKING.md) for full workflow, artifact interpretation, and troubleshooting.
