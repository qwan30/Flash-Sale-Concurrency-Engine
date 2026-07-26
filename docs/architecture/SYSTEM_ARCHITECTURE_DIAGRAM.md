# System Architecture Diagram

This document provides a visual representation of the physical and logical architecture of the Flash-Sale Concurrency Engine.

---

## 1. System Architecture Layout

Below is the network, data, and container layout of the system. In production or benchmark runs, Nginx routes requests to the backend instances, which interact with Redis for fast stock checks, MySQL for durable persistence, and Kafka for asynchronous message publishing via the outbox pattern.

```mermaid
graph TB
    %% Client Layer
    subgraph Clients [Client Layer]
        Browser["Operator Dashboard (Next.js/React - Port 3000)"]
        JMeter["JMeter Benchmark Client (PowerShell CLI)"]
    end

    %% Gateway Layer
    subgraph Gateway [Ingress Gateway]
        Nginx["Nginx Reverse Proxy (Port 80)"]
    end

    %% Application Layer
    subgraph Application [Backend Application (Java 21 / Spring Boot - Port 1122)]
        direction TB
        subgraph Layers [DDD Layers]
            Controller["Controller Layer<br>(REST API & Swagger UI)"]
            AppService["Application Layer<br>(Use-cases, Strategies, Outbox)"]
            Domain["Domain Layer<br>(Entities, Domain Services, Ports)"]
            InfraAdapt["Infrastructure Adapters<br>(Redis/Redisson Client, JPA)"]
            
            Controller --> AppService
            AppService --> Domain
            InfraAdapt -- Implements Ports --> Domain
        end
    end

    %% Caching Layer
    subgraph Cache [Caching & Lock Layer]
        Redis["Redis (Port 6319)<br>- TICKET:{id}:STOCK (Fast Gate)<br>- Lua Scripts (Atomic Decr)"]
        Redisson["Redisson Client<br>- Distributed Locks"]
    end

    %% Data Layer
    subgraph Data [Data Layer (MySQL 8.0)]
        MySQL["MySQL Database (Port 3316)<br>- ticket (Fixture)<br>- ticket_order_YYYYMM (Dynamic Monthly Tables)<br>- outbox_event (Outbox Table)"]
    end

    %% Messaging Layer
    subgraph Message [Messaging Broker]
        Kafka["Apache Kafka (Port 9094 - KRaft)<br>- topic: flashsale.orders"]
    end

    %% Observability Layer
    subgraph Observability [Observability & Monitoring]
        Prometheus["Prometheus Server (Port 9090)<br>- Pulls metrics from Actuator"]
        Grafana["Grafana Dashboard (Port 3000)<br>- Performance Visualization"]
        ELK["ELK Stack (Port 5601)<br>- Logstash -> ES -> Kibana"]
    end

    %% Connections
    Browser -->|HTTP Requests| Nginx
    JMeter -->|HTTP Load Test| Nginx
    
    Nginx -->|Proxy Pass /api to port 1122| Controller
    Nginx -->|Serves Static Files| Static["Static Files (/static)"]
    
    InfraAdapt -->|Read/Write Cache & Locks| Redis
    InfraAdapt -->|Read/Write Data & Outbox| MySQL
    
    AppService -->|Outbox Scheduler Polls & Sends| Kafka
    
    Prometheus -->|Scrapes /actuator/prometheus| Controller
    Grafana -->|Queries| Prometheus
    ELK -->|Collects Logs| Layers
```

---

## 2. Key Component Communication Flow

1. **Request Ingress:** The `Browser` or `JMeter` sends request to `Nginx` on port 80.
2. **Reverse Proxy:** `Nginx` proxies `/api` requests to the Spring Boot `Controller` (running on Java 21 Virtual Threads) and serves web assets (`/static`) directly from the disk.
3. **High-Concurrency Stock Deduction:**
   - The application checks and decrements stock in `Redis` using atomic Lua scripts (optimistic fast gate).
   - If Redis stock deduction succeeds, it inserts the order row in `MySQL`.
   - If the database write fails, the application triggers a **compensation mechanism** to restore the deducted stock in `Redis`.
4. **Asynchronous Event Publishing (Outbox):**
   - The order insert and an outbox event write happen in the same MySQL transaction (`@Transactional`).
   - A background scheduler polls the `outbox_event` table and publishes events to `Kafka` for downstream consumers.
5. **Observability Scrape:** `Prometheus` scrapes Spring Boot Actuator endpoint (`/actuator/prometheus`) and database/redis exporters, which `Grafana` queries to draw real-time benchmark charts.
