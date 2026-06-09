# ── Build stage ──────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Layer 1: root POM + Maven wrapper (cache invalidation: rare)
COPY pom.xml ./
COPY app/backend/xxxx-domain/pom.xml      app/backend/xxxx-domain/pom.xml
COPY app/backend/xxxx-application/pom.xml app/backend/xxxx-application/pom.xml
COPY app/backend/xxxx-infrastructure/pom.xml app/backend/xxxx-infrastructure/pom.xml
COPY app/backend/xxxx-controller/pom.xml  app/backend/xxxx-controller/pom.xml
COPY app/backend/xxxx-start/pom.xml       app/backend/xxxx-start/pom.xml

# Pre-fetch dependencies (layer cached unless pom.xml changes)
RUN mvn -B dependency:go-offline -pl app/backend/xxxx-start -am -q || true

# Layer 2: source code (cache invalidation: every push)
COPY app/backend/ app/backend/

# Build the fat JAR (tests already passed in CI)
RUN mvn -B -q -pl app/backend/xxxx-start -am -DskipTests package \
    && cp app/backend/xxxx-start/target/xxxx-start-*.jar /app.jar

# ── Runtime stage ────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S flashsale && adduser -S flashsale -G flashsale

COPY --from=build /app.jar /app/app.jar

USER flashsale
EXPOSE 1122

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:1122/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
