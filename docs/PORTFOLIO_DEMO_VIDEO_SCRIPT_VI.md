# Kịch bản quay video demo portfolio — Flash-Sale Concurrency Engine

## 1. Mục tiêu video

Video dài khoảng **7 phút**, tập trung chứng minh năng lực backend qua một câu chuyện xuyên suốt:

> Khi hàng nghìn request cùng tranh mua một lượng tồn kho hữu hạn, hệ thống phải từ chối phần nhu cầu vượt quá tồn kho mà không oversell, đồng thời giữ Redis và MySQL nhất quán khi có lỗi từng phần.

Định vị dự án đúng với source hiện tại:

- Đây là **backend reliability lab về flash-sale concurrency**, không phải hệ thống bán vé production hoàn chỉnh.
- Phần frontend là giao diện điều khiển và quan sát lab.
- Điểm mạnh nhất là luồng có thể kiểm chứng: **reset → warmup → order/load → consistency check**.
- Kết quả benchmark được trình bày như **artifact local có ngày, máy và cấu hình cụ thể**, không phải cam kết hiệu năng trên mọi môi trường.

## 2. Những phần tốt nhất nên demo

| Ưu tiên | Nội dung | Giá trị portfolio | Bằng chứng chính trong source |
|---|---|---|---|
| 1 | Bốn chiến lược trừ tồn kho | Thể hiện tư duy so sánh từ baseline sai đến giải pháp an toàn hơn | `OrderStrategy.java`, package `service/order/strategy/` |
| 2 | Redis Lua atomic gate | Check và decrement tồn kho trong một lệnh phía Redis | `RedisCacheStoreAdapter.java` |
| 3 | Compensation khi ghi DB/order thất bại | Xử lý dual-write giữa Redis và MySQL | `RedisLuaCompensatingStockDeductionStrategy.java`, `OrderCreationService.java` |
| 4 | Reconciliation | Phát hiện và sửa drift còn sót, lấy MySQL làm source of truth | `OrderReconciliationService.java` |
| 5 | Benchmark có artifact | Có quy trình tái lập và dữ liệu để kiểm chứng thay vì chỉ tuyên bố | `benchmark/run-jmeter.ps1`, `benchmark/experiment-spec.json`, `benchmark/results/` |
| 6 | Consistency dashboard | Biến correctness thành số liệu nhìn thấy được | `AdminBenchmarkController.java`, `consistency-dashboard.tsx` |
| 7 | Idempotency và transactional outbox | Cho thấy có suy nghĩ về retry và event delivery | `IdempotencyService.java`, package `application/MQ/` |

## 3. Kết quả source audit cần nhớ khi nói

### Luồng tạo order

`POST /orders` đi qua rate limiter, validation, idempotency, strategy registry, trừ tồn kho, ghi order và ghi outbox event. Nếu strategy yêu cầu compensation và phần ghi order thất bại, `OrderCreationService` phục hồi lượng tồn kho đã trừ trong Redis.

### Bốn strategy hiện có

1. `UNSAFE_DB`: cố ý update DB không có điều kiện đủ tồn kho để tạo baseline có thể oversell.
2. `CONDITIONAL_DB`: một câu `UPDATE ... WHERE stockAvailable >= quantity`; số row bị ảnh hưởng bằng 0 được xem là sold-out/rejection hợp lệ.
3. `REDIS_LUA`: Redis kiểm tra và trừ trước bằng Lua, sau đó MySQL vẫn giữ conditional update làm guard cho source of truth; phiên bản này không bù Redis nếu lỗi xảy ra về sau.
4. `REDIS_LUA_WITH_COMPENSATION`: Redis Lua gate cộng restore tồn kho khi DB hoặc order write không hoàn tất.

### Reconciliation

`OrderReconciliationService` chạy mỗi 30 giây cho ticket mặc định ID `4`, so sánh Redis với `stockAvailable` trong MySQL và sửa Redis về giá trị DB khi có drift. Đây là cơ chế recovery của lab, chưa phải reconciliation đa SKU hoặc đa instance.

### Idempotency và outbox

- Idempotency hiện dùng `ConcurrentHashMap`, chỉ có hiệu lực trong một process và không bền vững qua restart.
- Outbox lưu event cùng transaction với order, sau đó scheduler publish sang Kafka theo batch và retry có giới hạn. Không nói “đảm bảo delivery vô hạn”; source cấu hình `maxAttempts` mặc định là 5.

### Artifact benchmark nên dùng trong video

Run đã lưu: `REDIS_LUA_WITH_COMPENSATION-20260713-002339`

| Thuộc tính | Giá trị |
|---|---:|
| Ngày chạy | 2026-07-13 |
| Môi trường | Máy local `ACER` |
| Tổng request | 5.000 |
| Concurrent threads | 100 |
| Tồn kho ban đầu | 1.000 |
| Order được chấp nhận | 1.000 |
| Request bị từ chối do hết tồn kho | 4.000 |
| Throughput đo được | 142,1 req/s |
| Average latency | 643,16 ms |
| P95 / P99 | 2.055 / 2.471 ms |
| Oversold | 0 |
| Redis–DB inconsistency | 0 |
| Drift | 0 |

Điểm quan trọng khi diễn giải: **4.000 request bị từ chối là kết quả business mong đợi vì chỉ có 1.000 vé**, không phải 4.000 lỗi HTTP hay lỗi hệ thống.

## 4. Chuẩn bị trước khi quay

### 4.1. Khởi động hệ thống

Tại root repository:

```powershell
docker compose -f environment/docker-compose-dev.yml up -d
mvn -pl app/backend/xxxx-start -am spring-boot:run
```

Mở một terminal khác:

```powershell
Set-Location app/frontend
npm run dev
```

Kiểm tra nhanh:

```powershell
Invoke-RestMethod http://localhost:1122/actuator/health
```

Kết quả cần có: backend `UP`, frontend mở được tại `http://localhost:3000`.

### 4.2. Chuẩn bị các tab theo thứ tự

1. `http://localhost:3000/`
2. `http://localhost:3000/admin/control-desk`
3. `http://localhost:3000/booking`
4. `http://localhost:3000/admin/consistency`
5. `http://localhost:1122/swagger-ui.html`
6. `benchmark/results/REDIS_LUA_WITH_COMPENSATION-20260713-002339/html/index.html`
7. IDE mở sẵn các file:
   - `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/model/order/OrderStrategy.java`
   - `app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/cache/redis/RedisCacheStoreAdapter.java`
   - `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/OrderCreationService.java`
   - `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/OrderReconciliationService.java`
   - `benchmark/results/REDIS_LUA_WITH_COMPENSATION-20260713-002339/run.json`

### 4.3. Tạo trạng thái demo sạch

Trên Control Desk:

- Ticket ID: `4`
- Stock: `20` để dễ nhìn thấy thay đổi
- Year Month: tháng hiện tại theo định dạng `yyyyMM`
- Bấm `Reset`
- Bấm `Warm Redis`
- Bấm `Check consistency`

Trước khi bắt đầu quay, cần thấy:

- DB stock = 20
- Redis stock = 20
- Orders = 0
- Oversold = 0
- Drift = 0

### 4.4. Quy tắc quay để tránh demo sai

- Không bấm phần `Start Benchmark`/`Real-time Metrics` trên đầu trang Benchmark: stream metric ở đó hiện là dữ liệu mô phỏng và danh sách strategy có tên cũ không khớp backend.
- Không bấm `Seed Data`: nút này hiện chỉ hiển thị alert ở frontend.
- Chỉ dùng bảng Benchmark khi dữ liệu có trạng thái `PASS` và run ID thật. Nếu trang báo fallback hoặc hiển thị `SAMPLE`, chuyển sang artifact HTML/JSON đã lưu.
- Không chạy full 5.000 request trong lúc quay nếu máy không ổn định. Dùng artifact đã lưu để video ngắn và có kết quả chắc chắn.
- Không để terminal lộ nội dung `.env`, mật khẩu hoặc token.

## 5. Kịch bản nói chi tiết — bản khoảng 7 phút

### 0:00–0:30 — Hook: vấn đề cần giải quyết

**Màn hình:** Trang Home, đặt con trỏ gần dòng “Prove stock correctness under load”.

**Thao tác:** Giữ khung hình ở hero và hai nút chính; không cần cuộn xuống KPI throughput trên Home vì video sẽ dùng một run có ngày cụ thể ở phần benchmark.

**Lời nói:**

> Đây là Flash-Sale Concurrency Engine, một backend reliability lab tôi xây dựng để trả lời một câu hỏi rất thực tế: khi hàng nghìn request cùng mua một lượng vé hữu hạn, làm sao hệ thống không bán vượt tồn kho và vẫn giữ Redis với MySQL nhất quán? Trong video này tôi sẽ không chỉ trình bày giao diện, mà sẽ tạo một order thật, kiểm tra trạng thái dữ liệu và mở artifact benchmark để chứng minh kết quả.

### 0:30–1:05 — Phạm vi và kiến trúc

**Màn hình:** Chuyển nhanh sang sơ đồ kiến trúc trong `README.md` hoặc thu nhỏ IDE để thấy năm Maven module.

**Lời nói:**

> Dự án được tổ chức theo DDD multi-module. Domain giữ entity và contract; Application điều phối use case và các chiến lược trừ tồn kho; Infrastructure triển khai MySQL, Redis và Kafka; Controller cung cấp REST API; còn Start là entry point Spring Boot cùng scheduling, actuator và OpenAPI. Frontend Next.js chỉ là operator surface để quan sát lab, không phải một website bán vé production.

**Điểm cần chỉ trên màn hình:**

- `xxxx-domain`
- `xxxx-application`
- `xxxx-infrastructure`
- `xxxx-controller`
- `xxxx-start`

### 1:05–1:50 — Chuẩn bị một flash sale có trạng thái xác định

**Màn hình:** `/admin/control-desk`.

**Thao tác:** Nhập ticket `4`, stock `20`, year-month hiện tại; lần lượt bấm `Reset`, `Warm Redis`, `Check consistency`.

**Lời nói:**

> Trước mỗi thí nghiệm, tôi đưa hệ thống về một fixture có thể tái lập. Reset đưa tồn kho MySQL về 20 và xóa order của tháng thử nghiệm. Warmup đồng bộ số tồn kho đó sang Redis. Cuối cùng, consistency check đọc cả hai store. Hiện tại Redis bằng 20, MySQL bằng 20, chưa có order, oversold bằng 0 và drift bằng 0. Việc cố định trạng thái ban đầu giúp kết quả sau tải có thể kiểm tra lại, thay vì chỉ nhìn vào số request trên màn hình.

**Dừng 2 giây:** Để người xem nhìn rõ hai số stock và drift.

### 1:50–2:40 — Tạo order bằng chiến lược có compensation

**Màn hình:** `/booking`.

**Thao tác:**

1. User ID: `42001`
2. Quantity: `1`
3. Mở `Advanced lab settings`
4. Chọn `Redis compensation`
5. Bấm `Submit order probe`

**Lời nói:**

> Tôi gửi một order có quantity bằng 1 qua chiến lược Redis Lua with compensation. Request mang một idempotency key để một retry trùng key không trừ kho lần thứ hai. Ở fast path, Lua kiểm tra đủ tồn kho và decrement ngay trong một thao tác atomic tại Redis. MySQL vẫn thực hiện conditional update để giữ vai trò source of truth. Khi order được chấp nhận, response trả về order number cùng stock sau giao dịch.

**Sau khi response xuất hiện, nói:**

> Order đã được chấp nhận. Tồn kho hiển thị giảm từ 20 xuống 19. Tiếp theo tôi sẽ kiểm tra con số này độc lập qua consistency endpoint.

### 2:40–3:15 — Chứng minh Redis và MySQL nhất quán

**Màn hình:** `/admin/consistency`.

**Thao tác:** Nhập đúng ticket `4` và year-month, bấm `Refresh snapshot`.

**Lời nói:**

> Snapshot này không lấy một con số cache rồi suy luận. Backend đọc riêng Redis stock, DB stock và số order. Sau một giao dịch, Redis bằng 19, MySQL bằng 19, order count bằng 1, oversold bằng 0 và drift bằng 0. Đây là invariant tôi quan tâm: lượng order được chấp nhận không vượt tồn kho, và hai store không lệch nhau ở cuối luồng.

### 3:15–4:05 — Từ baseline nguy hiểm đến Redis Lua gate

**Màn hình:** IDE, mở `OrderStrategy.java`, sau đó `RedisCacheStoreAdapter.java`.

**Thao tác:** Highlight bốn enum, rồi highlight đoạn Lua `GET`, điều kiện đủ stock và `SET` giá trị mới.

**Lời nói:**

> Để thấy trade-off thay vì chỉ có một lời giải, source giữ bốn strategy. UNSAFE_DB là baseline cố ý update mà không có điều kiện đủ tồn kho. CONDITIONAL_DB đưa check và decrement vào cùng một câu update có điều kiện. REDIS_LUA dùng Redis làm cổng nhận hoặc từ chối request trước khi tạo contention ở DB. Cuối cùng, REDIS_LUA_WITH_COMPENSATION bổ sung đường phục hồi khi Redis đã trừ nhưng bước phía database không hoàn tất.

> Đoạn Lua này chạy atomic phía Redis: nếu key chưa được warmup hoặc stock không đủ, request bị từ chối; nếu đủ, script cập nhật stock và trả về lượng còn lại. Vì vậy hai request không thể cùng đọc một giá trị cũ rồi cùng ghi đè lên nhau trong Redis.

### 4:05–4:55 — Compensation và reconciliation

**Màn hình:** `OrderCreationService.java`, sau đó `OrderReconciliationService.java`.

**Thao tác:**

- Highlight nhánh `compensateOnOrderFailure` và `restoreStockCache`.
- Chuyển sang đoạn tính `drift` và `setStockCache` trong reconciliation.

**Lời nói:**

> Atomic trong Redis chưa giải quyết toàn bộ bài toán, vì Redis và MySQL không nằm trong cùng một transaction. Nếu Redis đã decrement nhưng ghi order thất bại, OrderCreationService đánh dấu transaction database rollback và thực hiện compensation để cộng tồn kho Redis trở lại.

> Nếu chính bước compensation cũng thất bại, ví dụ JVM hoặc Redis gặp sự cố đúng thời điểm đó, reconciliation là lớp phục hồi thứ hai. Job hiện chạy mỗi 30 giây cho fixture ticket 4, lấy stock MySQL làm source of truth, tính drift và sửa Redis về giá trị đúng. Tôi cố ý gọi đây là cơ chế của lab; để chạy production đa instance còn cần quét nhiều SKU và leader election.

### 4:55–6:05 — Benchmark có artifact và cách đọc kết quả

**Màn hình:** Mở `run.json`, sau đó mở JMeter HTML report của run `REDIS_LUA_WITH_COMPENSATION-20260713-002339`.

**Thao tác:** Highlight lần lượt `totalRequests`, `concurrency`, `successOrders`, `failedOrders`, `oversoldCount`, `redisDbInconsistencyCount`, `status`.

**Lời nói:**

> Phần quan trọng nhất của dự án là kết quả được lưu thành artifact, không chỉ xuất hiện thoáng qua trên console. Run local ngày 13 tháng 7 năm 2026 dùng 5.000 request, 100 concurrent threads và tồn kho ban đầu là 1.000.

> Hệ thống chấp nhận đúng 1.000 order và từ chối 4.000 request còn lại vì đã sold out. Bốn nghìn request này là business rejection mong đợi, không phải bốn nghìn lỗi hệ thống. Trong run này, oversold bằng 0, Redis–DB inconsistency bằng 0 và drift bằng 0.

> Throughput đo được là 142,1 request mỗi giây, average latency 643,16 mili giây, P95 là 2.055 mili giây và P99 là 2.471 mili giây. Tôi xem các số latency và throughput là kết quả của đúng máy và đúng run này; bằng chứng có ý nghĩa bền vững hơn là invariant correctness đi kèm artifact reset, warmup, raw JTL, HTML report và consistency snapshot.

### 6:05–6:35 — Hai cơ chế reliability bổ trợ

**Màn hình:** IDE split view với `IdempotencyService.java` và `OutboxService.java`.

**Lời nói:**

> Luồng còn có hai cơ chế bổ trợ. Idempotency dùng cặp user ID và idempotency key để retry cùng key trả lại cùng response trong một process, nhưng hiện chưa phải distributed store. Transactional outbox ghi event cùng transaction với order rồi scheduler relay sang Kafka theo batch và retry có giới hạn. Tôi giữ rõ các giới hạn này để phân biệt một pattern đã được triển khai trong lab với một cam kết production.

### 6:35–7:00 — Kết thúc

**Màn hình:** Quay lại Home hoặc trang Consistency có snapshot sạch.

**Lời nói:**

> Tóm lại, dự án này thể hiện ba năng lực chính của tôi: thiết kế nhiều chiến lược concurrency để so sánh trade-off, xử lý lỗi từng phần giữa Redis và MySQL bằng compensation cộng reconciliation, và xây benchmark có artifact để mọi kết quả đều có thể kiểm chứng. Source code, tài liệu kiến trúc và dữ liệu benchmark đều có trong repository. Cảm ơn bạn đã xem demo.

## 6. Bản rút gọn khoảng 3 phút

Nếu portfolio chỉ cho phép video ngắn, giữ bốn cảnh:

1. **0:00–0:25:** Nêu bài toán oversell và Redis–MySQL consistency.
2. **0:25–1:00:** Reset 20 → warm Redis → consistency sạch.
3. **1:00–1:40:** Tạo một order với Redis compensation → stock 19 ở cả Redis và DB.
4. **1:40–2:30:** Mở code Lua + compensation + reconciliation.
5. **2:30–3:00:** Mở `run.json`, nói 5.000 request / 100 threads / 1.000 accepted / 4.000 sold-out rejection / 0 oversold / 0 drift.

Lời kết rút gọn:

> Điểm tôi muốn chứng minh không chỉ là hệ thống nhận được bao nhiêu request, mà là sau tải, dữ liệu vẫn đúng và kết quả có artifact để người khác kiểm tra lại.

## 7. Checklist trước khi bấm Record

- [ ] Backend health trả `UP`.
- [ ] Frontend không có banner lỗi gọi backend.
- [ ] Ticket 4 đã reset và warmup.
- [ ] Year-month trên Control Desk, Booking và Consistency khớp nhau.
- [ ] Stock ban đầu nhỏ, dễ quan sát, ví dụ 20.
- [ ] IDE tăng font lên ít nhất 18 px và chỉ mở các file cần nói.
- [ ] JMeter artifact HTML mở được trước khi quay.
- [ ] Không có `.env`, token hoặc password trên màn hình.
- [ ] Tắt notification của Windows, Discord, Slack và email.
- [ ] Quay 1080p, 30 FPS; con trỏ di chuyển chậm và dừng 1–2 giây ở mỗi con số.
- [ ] Không gọi 4.000 sold-out rejection là system error.
- [ ] Không dùng từ “production-ready”, “guarantee trên mọi môi trường” hoặc “benchmark nhanh nhất” nếu không có phép đo mới tương ứng.

## 8. B-roll và ảnh dự phòng

Nếu live demo gặp lỗi, dùng các ảnh trong `screen-demo/`:

- `01-home.png`
- `04-booking.png`
- `07-admin-control-desk.png`
- `09-admin-consistency.png`

Với benchmark, ưu tiên artifact thật `benchmark/results/REDIS_LUA_WITH_COMPENSATION-20260713-002339/` thay vì ảnh `08-admin-benchmark.png`, vì ảnh đó đang hiển thị sample fallback.

## 9. Câu trả lời ngắn nếu nhà tuyển dụng hỏi thêm

**Vì sao vẫn update MySQL có điều kiện sau Redis Lua?**

> Redis giúp chặn sớm nhu cầu vượt tồn kho, còn MySQL vẫn là source of truth. Conditional update phía DB là guard cuối để không phụ thuộc tuyệt đối vào cache state.

**Compensation có đủ để bảo đảm không drift không?**

> Không tuyệt đối, vì compensation cũng có thể thất bại hoặc JVM có thể chết giữa hai bước. Vì vậy lab có thêm reconciliation; production cần mở rộng nó cho nhiều SKU và phối hợp giữa nhiều instance.

**Tại sao 4.000 request bị từ chối mà status vẫn PASS?**

> Vì workload có 5.000 request nhưng tồn kho chỉ có 1.000. Chấp nhận đúng 1.000 và từ chối 4.000 là hành vi business đúng; PASS dựa trên không oversell và không drift ở cuối run.

**142,1 req/s có phải hiệu năng hệ thống production không?**

> Không. Đó là một phép đo local có ngày, máy và cấu hình cụ thể. Tôi dùng nó để chứng minh benchmark có thể tái lập; không ngoại suy thành SLA hay năng lực production.

**Idempotency đã dùng được khi scale ngang chưa?**

> Chưa. Bản hiện tại dùng `ConcurrentHashMap` trong một process. Khi scale ngang, tôi sẽ chuyển key và response sang Redis hoặc dùng uniqueness constraint trong database tùy yêu cầu durability.
