# xxxx-domain — Tầng Domain (Domain Layer)

> **Vai trò:** Trái tim của hệ thống — chứa toàn bộ **logic nghiệp vụ thuần túy**, không phụ thuộc vào bất kỳ framework hay công nghệ nào (không Spring, không JPA, không Redis).

---

## 📐 Vị trí trong kiến trúc DDD + Clean Architecture

```
                    ┌─────────────────────┐
                    │   xxxx-controller   │  ← Nhận HTTP request
                    └────────┬────────────┘
                             │ gọi
                    ┌────────▼────────────┐
                    │  xxxx-application   │  ← Điều phối use-case
                    └────────┬────────────┘
                             │ gọi
               ╔═════════════▼═════════════╗
               ║     ★ xxxx-domain ★       ║  ← BẠN ĐANG Ở ĐÂY
               ╚═════════════╤═════════════╝
                             │ interface (Port)
                    ┌────────▼────────────┐
                    │ xxxx-infrastructure  │  ← Triển khai cụ thể (MySQL, Redis)
                    └─────────────────────┘
```

**Quy tắc quan trọng:** Domain layer **KHÔNG BAO GIỜ** import từ tầng khác. Nó chỉ **khai báo interface** (Port) và để tầng Infrastructure **implement**.

---

## 📁 Cấu trúc thư mục

```
domain/
├── model/                  ← Các đối tượng nghiệp vụ
│   ├── entity/             ← Domain Entities (đối tượng có identity)
│   │   ├── Ticket.java         ← Entity: sự kiện bán vé (id, name, status, time)
│   │   ├── TickerOrder.java    ← Entity: đơn hàng (orderNumber, totalAmount, userId)
│   │   └── TicketDetail.java   ← Entity: chi tiết vé (stock, price, version)
│   └── enums/              ← Enum nghiệp vụ (dự phòng, chưa sử dụng)
│
├── repository/             ← Port — Interface giao tiếp dữ liệu
│   ├── OrderDeductionRepository.java  ← CRUD đơn hàng theo bảng tháng (yyyyMM)
│   ├── TickerOrderRepository.java     ← Trừ stock (CAS, Level-based)
│   ├── TicketDetailRepository.java    ← Đọc chi tiết vé
│
├── service/                ← Domain Services — Logic nghiệp vụ
│   ├── OrderDeductionDomainService.java      ← Interface: quản lý đơn hàng
│   ├── TickerOrderDomainService.java         ← Interface: trừ stock
│   ├── TicketDetailDomainService.java        ← Interface: đọc chi tiết vé
│   └── impl/                                ← Triển khai cụ thể
│       ├── OrderDeductionDomainServiceImpl.java
│       ├── TickerOrderDomainServiceImpl.java
│       ├── TicketDetailDomainServiceImpl.java
│
└── event/                  ← Domain Events — Sự kiện nghiệp vụ
    ├── OrderEvent.java             ← Event: ORDER_CREATED, ORDER_REJECTED, STOCK_EXHAUSTED
    └── ReconciliationEvent.java    ← Event: đối soát Redis ↔ MySQL
```

---

## 🔄 Luồng dữ liệu bên trong Domain

### Khi Application gọi vào Domain:

```
Application Service
       │
       ▼
Domain Service Interface        (VD: OrderDeductionDomainService)
       │
       ▼
Domain Service Impl             (VD: OrderDeductionDomainServiceImpl)
       │
       ▼
Repository Interface (Port)     (VD: OrderDeductionRepository)
       │
       ▼
[Tầng Infrastructure sẽ implement interface này]
```

### Ví dụ cụ thể — Tạo đơn hàng:

```
OrderCreationService (Application)
       │
       │  gọi insertOrder(yearMonth, tickerOrder)
       ▼
OrderDeductionDomainService (interface)
       │
       ▼
OrderDeductionDomainServiceImpl (impl)
       │
       │  ủy quyền cho repository
       ▼
OrderDeductionRepository (interface / Port)
       │
       ▼
OrderDeductionInfrasRepositoryImpl (Infrastructure — native SQL)
```

---

## 🧩 Giải thích từng folder

### `model/entity/` — Domain Entities

Đây là các đối tượng đại diện cho **khái niệm nghiệp vụ cốt lõi**:

| Entity | Mô tả | Thuộc tính chính |
|--------|--------|------------------|
| `Ticket` | Sự kiện bán vé (concert, show) | `id`, `name`, `description`, `status`, `startTime`, `endTime` |
| `TickerOrder` | Đơn hàng đặt vé | `orderNumber`, `userId`, `totalAmount`, `terminalId`, `orderDate` |
| `TicketDetail` | Chi tiết vé (stock, giá) | `ticketDetailId`, `stockAvailable`, `unitPrice`, `version` |

### `repository/` — Port (Interface)

Khai báo **hợp đồng** truy xuất dữ liệu mà Domain cần, nhưng **KHÔNG** quy định cách triển khai:

| Repository | Chức năng |
|------------|-----------|
| `OrderDeductionRepository` | Tạo bảng tháng, insert/query đơn hàng, đếm, xóa |
| `TickerOrderRepository` | Trừ stock (3 chiến lược: unsafe, CAS, level-based) |
| `TicketDetailRepository` | Đọc chi tiết vé theo ID |

### `service/` — Domain Service

Chứa logic nghiệp vụ, giao tiếp qua interface:

- **Interface** (`OrderDeductionDomainService`): khai báo các phương thức
- **Impl** (`OrderDeductionDomainServiceImpl`): triển khai bằng cách ủy quyền xuống Repository

> **Tại sao Service chỉ delegate xuống Repository?**
> Vì logic nghiệp vụ phức tạp (trừ stock, idempotency, compensation) được xử lý ở tầng **Application**. Domain Service đóng vai trò **gateway an toàn** — nếu sau này cần thêm business rule (validate, audit log), chỉ cần sửa ở Impl mà không ảnh hưởng Application.

### `event/` — Domain Events

Sự kiện nghiệp vụ phát sinh khi thay đổi trạng thái domain:

| Event | Mô tả | Loại sự kiện |
|-------|--------|-------------|
| `OrderEvent` | Vòng đời đơn hàng | `ORDER_CREATED`, `ORDER_REJECTED`, `STOCK_EXHAUSTED` |
| `ReconciliationEvent` | Đối soát stock Redis ↔ MySQL | `RECONCILIATION_COMPLETED` |

Các event này được ghi vào **Transactional Outbox** (tầng Application) rồi publish lên Kafka.

---

## 📏 Nguyên tắc thiết kế

1. **Dependency Rule (Quy tắc phụ thuộc):** Domain không import bất kỳ tầng nào khác. Chỉ các tầng bên ngoài mới phụ thuộc vào Domain.
2. **Interface-first:** Mọi giao tiếp ra ngoài đều qua interface (Port). Tầng Infrastructure sẽ cung cấp Adapter.
3. **Technology-agnostic:** Domain entities **không** chứa annotation JPA (trừ `Ticket.java` — đây là technical debt nên refactor).
4. **Rich Domain Model:** Entities nên chứa business logic thay vì chỉ là data holder (hiện tại đang ở dạng Anemic Domain Model — có thể cải thiện).

---

## 🔗 Phụ thuộc Maven

```
xxxx-domain không phụ thuộc module nào khác trong project
     └── Chỉ dùng: Lombok, Jakarta Persistence API (minimal)
```
