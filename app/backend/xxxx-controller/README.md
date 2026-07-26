# xxxx-controller — Tầng Controller (Adapter Layer — Inbound)

> **Vai trò:** **Cổng vào** của hệ thống — nhận HTTP request từ client, chuyển đổi thành lời gọi Application Service, và trả kết quả dưới dạng JSON.

---

## 📐 Vị trí trong kiến trúc DDD + Clean Architecture

```
               ╔═════════════════════════╗
               ║  ★ xxxx-controller ★    ║  ← BẠN ĐANG Ở ĐÂY
               ╚═════════════╤═══════════╝
                             │ gọi
                    ┌────────▼────────────┐
                    │  xxxx-application   │  ← Điều phối use-case
                    └────────┬────────────┘
                             │
                    ┌────────▼────────────┐
                    │     xxxx-domain     │  ← Logic nghiệp vụ
                    └────────┬────────────┘
                             │
                    ┌────────▼────────────┐
                    │ xxxx-infrastructure  │  ← Implement cụ thể
                    └─────────────────────┘
```

**Vai trò chính:** Controller là **Inbound Adapter** — chuyển đổi giao thức HTTP sang lời gọi use-case. Controller KHÔNG chứa business logic.

---

## 📁 Cấu trúc thư mục

```
controller/
├── http/                       ← REST Controllers (HTTP endpoints)
│   ├── TicketOrderController.java         ← ★ API đặt hàng (core)
│   ├── TicketQueryController.java         ← API truy vấn vé
│   ├── TicketDetailController.java        ← API chi tiết vé + ping
│   ├── AdminBenchmarkController.java      ← API quản trị benchmark
│
├── model/                      ← Các object dùng cho request/response
│   ├── vo/                     ← Value Objects (response wrapper)
│   │   └── ResultMessage.java         ← Response envelope: success, code, message, result
│   │
│   └── enums/                  ← Enum + Utility
│       ├── ResultCode.java            ← Mã lỗi chuẩn (200, 400, 500, ...)
│       └── ResultUtil.java            ← Factory: tạo ResultMessage nhanh
│
└── rpc/                        ← RPC / gRPC endpoints (dự phòng cho microservice)
    └── (trống — sẵn sàng cho giao tiếp giữa các service)
```

---

## 🔄 Luồng dữ liệu — Từ HTTP Request đến Response

### Luồng tổng quát:

```
Client (Browser / JMeter / Dashboard)
    │
    │  HTTP Request (POST /orders, GET /tickets/4, ...)
    ▼
┌──────────────────────────────────────────┐
│            REST Controller               │
│  1. Parse request params / body          │
│  2. Gọi Application Service             │
│  3. Wrap kết quả vào ResultMessage       │
│  4. Trả HTTP Response (200/400/409)      │
└──────────────────────────────────────────┘
    │
    ▼
Application Service → Domain → Infrastructure → DB/Redis
```

### Chi tiết từng Controller:

---

### 1. `TicketOrderController` — API Đặt Hàng (Core)

| Endpoint | Method | Mô tả | Gọi đến |
|----------|--------|--------|---------|
| `/orders` | `POST` | ★ Tạo đơn hàng mới | `TicketOrderAppService.createOrder()` |
| `/orders` | `GET` | Danh sách đơn theo user + tháng | `TicketOrderAppService.findAllByUser()` |
| `/orders/{orderNumber}` | `GET` | Chi tiết 1 đơn hàng | `TicketOrderAppService.findByOrderNumber()` |
| `/order/{ticketId}/{qty}/order` | `GET` | ⚠️ Deprecated — trừ stock Level 1 | `TicketOrderAppService.decreaseStockLevel1()` |
| `/order/{ticketId}/{qty}/cas` | `GET` | ⚠️ Deprecated — trừ stock CAS | `TicketOrderAppService.decreaseStockLevel3CAS()` |

**HTTP Status mapping:**
```
CreateOrderResponse.code == "INVALID_REQUEST"  →  400 Bad Request
CreateOrderResponse.success == false           →  409 Conflict (sold out)
CreateOrderResponse.success == true            →  200 OK
```

---

### 2. `TicketQueryController` — API Truy Vấn Vé

| Endpoint | Method | Mô tả | Gọi đến |
|----------|--------|--------|---------|
| `/tickets/{ticketItemId}` | `GET` | Đọc chi tiết vé theo ID | `TicketDetailAppService.getTicketDetailById()` |

---

### 3. `TicketDetailController` — API Chi Tiết Vé + Ping

| Endpoint | Method | Mô tả | Gọi đến |
|----------|--------|--------|---------|
| `/ticket/ping/java` | `GET` | Ping (1s delay — test latency) | — |
| `/ticket/{ticketId}/detail/{detailId}` | `GET` | Đọc chi tiết vé | `TicketDetailAppService.getTicketDetailById()` |
| `/ticket/{ticketId}/detail/{detailId}/order` | `GET` | ⚠️ Deprecated — đặt hàng trực tiếp | `TicketDetailAppService.orderTicketByUser()` |

---

### 4. `AdminBenchmarkController` — API Quản Trị Benchmark

| Endpoint | Method | Mô tả | Gọi đến |
|----------|--------|--------|---------|
| `/admin/tickets/{id}/stock/warmup` | `POST` | Load stock vào Redis | `TicketOrderAppService.warmupStock()` |
| `/admin/benchmarks/reset` | `POST` | Reset data benchmark | `TicketOrderAppService.resetBenchmark()` |
| `/admin/benchmarks/consistency` | `GET` | Kiểm tra stock drift | `TicketOrderAppService.getConsistency()` |
| `/admin/benchmarks/reconcile` | `POST` | Đối soát Redis ↔ MySQL | `OrderReconciliationService.reconcile()` |
| `/admin/benchmarks/runs` | `GET` | Danh sách JMeter runs | `BenchmarkRunService.listRuns()` |
| `/admin/benchmarks/runs/{runId}` | `GET` | Chi tiết 1 benchmark run | `BenchmarkRunService.getRun()` |

---

### 5. Rate limiting on `POST /orders`

| Endpoint | Method | Mô tả |
|----------|--------|--------|
| `/hi` | `GET` | Hello World |
| `/hi/domain` | `GET` | Test domain layer |

---

## 🧩 Giải thích Model

### `ResultMessage<T>` — Response Envelope

Tất cả response đều được wrap trong `ResultMessage`:

```json
{
  "success": true,
  "code": 200,
  "message": "Thành công",
  "result": { ... }
}
```

### `ResultUtil` — Factory

```java
ResultUtil.data(object)     // → ResultMessage(success=true, code=200, result=object)
ResultUtil.error(code, msg) // → ResultMessage(success=false, code=code, message=msg)
```

### `ResultCode` — Mã lỗi chuẩn

Định nghĩa các mã lỗi HTTP + custom error code cho toàn bộ hệ thống.

---

## 📏 Nguyên tắc thiết kế

1. **Thin Controller:** Controller chỉ làm 3 việc: parse input → gọi Application Service → wrap response. **KHÔNG** chứa business logic.
2. **Response Standardization:** Mọi response đều qua `ResultMessage` để client parse nhất quán.
3. **Separation of Concerns:**
   - `http/` — REST endpoints
   - `rpc/` — gRPC/RPC endpoints (dự phòng cho microservice)
   - `model/` — VO/DTO dùng trong tầng Controller
4. **Deprecated APIs:** Các API cũ được đánh dấu `@Deprecated` nhưng vẫn giữ lại để benchmark plans cũ vẫn chạy được.

---

## 🧪 Unit Tests

```
controller/src/test/java/com/xxxx/ddd/controller/http/
├── TicketOrderControllerTest.java              ← Test POST /orders, GET /orders
├── TicketOrderControllerNegativeTest.java       ← Test case lỗi (invalid request)
├── TicketQueryControllerTest.java               ← Test GET /tickets/{id}
└── TicketDetailControllerTest.java              ← Test GET /ticket/{id}/detail/{id}
```

Chạy test: `mvn -pl app/backend/xxxx-controller -am test`

---

## 🔗 Phụ thuộc Maven

```
xxxx-controller
     ├── xxxx-application     ← Gọi Application Service interfaces
     ├── spring-boot-starter-web   ← @RestController, @GetMapping, @PostMapping
     └── lombok                    ← @Slf4j
```
