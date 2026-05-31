# Lab Operations

This page is kept for compatibility with older links.

Use [BENCHMARKING.md](./BENCHMARKING.md) for current setup, smoke, benchmark, dashboard, troubleshooting, and result-artifact guidance.

Core commands:

```bash
docker compose -f environment/docker-compose-dev.yml up -d
mvn -pl app/backend/xxxx-start -am -DskipTests package
java -jar app/backend/xxxx-start/target/xxxx-start-1.0-SNAPSHOT.jar
```

```powershell
powershell -ExecutionPolicy Bypass -File benchmark/smoke-local.ps1
powershell -ExecutionPolicy Bypass -File benchmark/run-jmeter.ps1 -Strategy REDIS_LUA_WITH_COMPENSATION
```
