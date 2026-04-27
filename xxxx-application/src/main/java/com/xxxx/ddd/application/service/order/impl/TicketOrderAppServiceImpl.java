package com.xxxx.ddd.application.service.order.impl;

import com.xxxx.ddd.application.model.TicketOrderDTO;
import com.xxxx.ddd.application.model.order.BenchmarkResetRequest;
import com.xxxx.ddd.application.model.order.BenchmarkResetResponse;
import com.xxxx.ddd.application.model.order.ConsistencySnapshot;
import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import com.xxxx.ddd.application.service.order.cache.StockOrderCacheService;
import com.xxxx.ddd.domain.model.entity.TickerOrder;
import com.xxxx.ddd.domain.service.OrderDeductionDomainService;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TicketOrderAppServiceImpl implements TicketOrderAppService {

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final BigDecimal UNIT_PRICE = BigDecimal.valueOf(5000);
    private static final String TERMINAL_ID = "OKX-SGN";

    @Autowired
    private TickerOrderDomainService tickerOrderDomainService;

    @Autowired
    private OrderDeductionDomainService orderDeductionDomainService;

    @Autowired
    private StockOrderCacheService stockOrderCacheService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private final Map<String, CreateOrderResponse> idempotencyCache = new ConcurrentHashMap<>();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        String validationError = validateCreateOrderRequest(request);
        if (validationError != null) {
            CreateOrderResponse response = CreateOrderResponse.failure(request, "INVALID_REQUEST", validationError);
            recordOrderMetric(request == null ? null : request.getStrategy(), false);
            return response;
        }

        String idempotencyKey = request.getUserId() + ":" + request.getIdempotencyKey();
        return idempotencyCache.computeIfAbsent(idempotencyKey, ignored -> doCreateOrder(request));
    }

    private CreateOrderResponse doCreateOrder(CreateOrderRequest request) {
        boolean redisDecremented = false;
        try {
            OrderStrategy strategy = request.getStrategy();
            long nowMillis = System.currentTimeMillis();
            String orderNumber = buildOrderNumber(request.getUserId(), nowMillis);
            String yearMonth = formatYearMonth(nowMillis);

            if (strategy == OrderStrategy.REDIS_LUA || strategy == OrderStrategy.REDIS_LUA_WITH_COMPENSATION) {
                long remainingStock = stockOrderCacheService.decreaseStockCacheByLuaReturningRemaining(
                        request.getTicketItemId(), request.getQuantity());
                if (remainingStock < 0) {
                    CreateOrderResponse response = failureWithCurrentStock(request, "REDIS_STOCK_UNAVAILABLE",
                            "Redis stock is missing or not enough");
                    recordOrderMetric(strategy, false);
                    return response;
                }
                redisDecremented = true;
            }

            boolean stockDecreased = switch (strategy) {
                case UNSAFE_DB -> tickerOrderDomainService.decreaseStockUnsafe(request.getTicketItemId(), request.getQuantity());
                case CONDITIONAL_DB, REDIS_LUA, REDIS_LUA_WITH_COMPENSATION ->
                        tickerOrderDomainService.decreaseStockLevel1(request.getTicketItemId(), request.getQuantity());
            };

            if (!stockDecreased) {
                if (redisDecremented && strategy == OrderStrategy.REDIS_LUA_WITH_COMPENSATION) {
                    stockOrderCacheService.restoreStockCache(request.getTicketItemId(), request.getQuantity());
                }
                CreateOrderResponse response = failureWithCurrentStock(request, "DB_STOCK_DECREMENT_FAILED",
                        "Database stock was not decremented");
                recordOrderMetric(strategy, false);
                return response;
            }

            orderDeductionDomainService.ensureMonthlyOrderTable(yearMonth);
            orderDeductionDomainService.insertOrder(yearMonth, buildOrder(request, orderNumber));

            CreateOrderResponse response = CreateOrderResponse.success(
                    request,
                    orderNumber,
                    stockOrderCacheService.getStockCache(request.getTicketItemId()),
                    tickerOrderDomainService.getStockAvailable(request.getTicketItemId())
            );
            recordOrderMetric(strategy, true);
            return response;
        } catch (Exception e) {
            markRollbackOnly();
            if (redisDecremented && request.getStrategy() == OrderStrategy.REDIS_LUA_WITH_COMPENSATION) {
                stockOrderCacheService.restoreStockCache(request.getTicketItemId(), request.getQuantity());
            }
            log.error("createOrder failed ticketItemId={} strategy={}", request.getTicketItemId(), request.getStrategy(), e);
            CreateOrderResponse response = failureWithCurrentStock(request, "ORDER_CREATE_FAILED",
                    "Order creation failed");
            recordOrderMetric(request.getStrategy(), false);
            return response;
        }
    }

    @Override
    public CreateOrderResponse warmupStock(Long ticketItemId) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setTicketItemId(ticketItemId);
        boolean warmed = stockOrderCacheService.addStockAvailableToCache(ticketItemId);
        int dbStock = ticketItemId == null ? -1 : tickerOrderDomainService.getStockAvailable(ticketItemId);
        return CreateOrderResponse.builder()
                .success(warmed)
                .code(warmed ? "SUCCESS" : "WARMUP_FAILED")
                .message(warmed ? "Redis stock warmed from database" : "Redis stock warmup failed")
                .ticketItemId(ticketItemId)
                .redisStockAfter(ticketItemId == null ? -1 : stockOrderCacheService.getStockCache(ticketItemId))
                .dbStockAfter(dbStock)
                .build();
    }

    @Override
    public BenchmarkResetResponse resetBenchmark(BenchmarkResetRequest request) {
        String yearMonth = normalizeYearMonth(request == null ? null : request.getYearMonth());
        if (request == null || request.getTicketItemId() == null || request.getStock() == null || request.getStock() < 0) {
            return BenchmarkResetResponse.builder()
                    .success(false)
                    .message("ticketItemId and non-negative stock are required")
                    .yearMonth(yearMonth)
                    .build();
        }

        tickerOrderDomainService.resetStock(request.getTicketItemId(), request.getStock());
        orderDeductionDomainService.ensureMonthlyOrderTable(yearMonth);
        orderDeductionDomainService.clearOrders(yearMonth);
        stockOrderCacheService.setStockCache(request.getTicketItemId(), request.getStock());

        return BenchmarkResetResponse.builder()
                .success(true)
                .message("Benchmark data reset")
                .ticketItemId(request.getTicketItemId())
                .stock(request.getStock())
                .yearMonth(yearMonth)
                .redisStockAfter(stockOrderCacheService.getStockCache(request.getTicketItemId()))
                .dbStockAfter(tickerOrderDomainService.getStockAvailable(request.getTicketItemId()))
                .dbOrderCount(orderDeductionDomainService.countOrders(yearMonth))
                .build();
    }

    @Override
    public ConsistencySnapshot getConsistency(Long ticketItemId, String yearMonth) {
        String normalizedYearMonth = normalizeYearMonth(yearMonth);
        int redisStock = ticketItemId == null ? -1 : stockOrderCacheService.getStockCache(ticketItemId);
        int dbStock = ticketItemId == null ? -1 : tickerOrderDomainService.getStockAvailable(ticketItemId);
        long orderCount = orderDeductionDomainService.countOrders(normalizedYearMonth);
        return ConsistencySnapshot.builder()
                .ticketItemId(ticketItemId)
                .yearMonth(normalizedYearMonth)
                .redisStockAfter(redisStock)
                .dbStockAfter(dbStock)
                .dbOrderCount(orderCount)
                .oversoldCount(Math.max(0, -dbStock))
                .redisDbInconsistencyCount(redisStock == dbStock ? 0 : 1)
                .build();
    }

    @Override
    public boolean decreaseStockLevel1(Long tickerId, int quantity) {
        CreateOrderRequest request = legacyRequest(tickerId, quantity, OrderStrategy.CONDITIONAL_DB);
        return createOrder(request).isSuccess();
    }


    @Override
    public boolean decreaseStockLevel2(Long tickerId, int quantity) {
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean decreaseStockLevel3CAS(Long tickerId, int quantity) {
        CreateOrderRequest request = legacyRequest(tickerId, quantity, OrderStrategy.REDIS_LUA_WITH_COMPENSATION);
        return createOrder(request).isSuccess();
    }

    @Override
    public int getStockAvailable(Long ticketId) {
        return tickerOrderDomainService.getStockAvailable(ticketId);
    }

    @Override
    public List<TicketOrderDTO> findAll(String yearMonth) {
        String normalizedYearMonth = normalizeYearMonth(yearMonth);
        orderDeductionDomainService.ensureMonthlyOrderTable(normalizedYearMonth);
        List<Object[]> results = orderDeductionDomainService.findAll(normalizedYearMonth);
        return mapOrderRows(results);

    }

    @Override
    public List<TicketOrderDTO> findAllByUser(String yearMonth, Long userId) {
        String normalizedYearMonth = normalizeYearMonth(yearMonth);
        orderDeductionDomainService.ensureMonthlyOrderTable(normalizedYearMonth);
        List<Object[]> results = orderDeductionDomainService.findAllByUser(normalizedYearMonth, userId);
        return mapOrderRows(results);
    }

    private List<TicketOrderDTO> mapOrderRows(List<Object[]> results) {
        return results.stream().map(row -> new TicketOrderDTO(
                (Integer) row[0],
                (Integer) row[1],
                (String) row[2],
                (BigDecimal) row[3],
                (String) row[4],
                ((Timestamp) row[5]).toLocalDateTime(), // Chuyển Timestamp sang LocalDateTime
                (String) row[6],
                ((Timestamp) row[7]).toLocalDateTime(), // Chuyển Timestamp sang LocalDateTime
                ((Timestamp) row[8]).toLocalDateTime()  // Chuyển Timestamp sang LocalDateTime
        )).toList();
    }

    @Override
    public boolean insertOrder(String yearMonth, TickerOrder tickerOrder) {
        String normalizedYearMonth = normalizeYearMonth(yearMonth);
        orderDeductionDomainService.ensureMonthlyOrderTable(normalizedYearMonth);
        orderDeductionDomainService.insertOrder(normalizedYearMonth, tickerOrder);
        return true;
    }

    @Override
    public TicketOrderDTO findByOrderNumber(String yearMonth, String orderNumber) {
        String nTable = extractYearMonthFromOrderNumber(orderNumber);
        orderDeductionDomainService.ensureMonthlyOrderTable(nTable);
        log.info("nTable: findByOrderNumber ={}", nTable);
        Object[] row = orderDeductionDomainService.findByOrderNumber(nTable, orderNumber);
//        return orderDeductionDomainService.findByOrderNumber(yearMonth, orderNumber);
        if(row == null){
            return null;
//            throw new EntityNotFoundException("Order not found: " + orderNumber);
        }
        return new TicketOrderDTO( // toMapStruct()
                ((Number) row[0]).intValue(),  // id
                ((Number) row[1]).intValue(),  // userId
                (String) row[2],               // orderNumber
                (BigDecimal) row[3],           // totalAmount
                (String) row[4],               // terminalId
                ((Timestamp) row[5]).toLocalDateTime(), // orderDate
                (String) row[6],               // orderNotes
                ((Timestamp) row[7]).toLocalDateTime(), // updatedAt
                ((Timestamp) row[8]).toLocalDateTime()  // createdAt
        );
    }
    // chuyển đổi
    private String extractYearMonthFromOrderNumber(String orderNumber) {
        try {
            // Lấy timestamp từ orderNumber
            String[] parts = orderNumber.split("-");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid order number format");
            }
            long timestamp = Long.parseLong(parts[parts.length - 1]);

            // Chuyển đổi timestamp thành LocalDateTime
            LocalDateTime dateTime = Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();

            // Format thành yyyyMM
            return dateTime.format(DateTimeFormatter.ofPattern("yyyyMM"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract yearMonth from orderNumber: " + orderNumber, e);
        }
    }

    private String validateCreateOrderRequest(CreateOrderRequest request) {
        if (request == null) {
            return "Request body is required";
        }
        if (request.getTicketItemId() == null || request.getTicketItemId() <= 0) {
            return "ticketItemId must be positive";
        }
        if (request.getUserId() == null || request.getUserId() <= 0) {
            return "userId must be positive";
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            return "quantity must be positive";
        }
        if (request.getStrategy() == null) {
            return "strategy is required";
        }
        if (!StringUtils.hasText(request.getIdempotencyKey())) {
            return "idempotencyKey is required";
        }
        return null;
    }

    private CreateOrderResponse failureWithCurrentStock(CreateOrderRequest request, String code, String message) {
        CreateOrderResponse response = CreateOrderResponse.failure(request, code, message);
        response.setRedisStockAfter(stockOrderCacheService.getStockCache(request.getTicketItemId()));
        response.setDbStockAfter(tickerOrderDomainService.getStockAvailable(request.getTicketItemId()));
        return response;
    }

    private TickerOrder buildOrder(CreateOrderRequest request, String orderNumber) {
        TickerOrder tickerOrderPlace = new TickerOrder();
        tickerOrderPlace.setUserId(request.getUserId().intValue());
        tickerOrderPlace.setOrderNumber(orderNumber);
        tickerOrderPlace.setTotalAmount(UNIT_PRICE.multiply(BigDecimal.valueOf(request.getQuantity())));
        tickerOrderPlace.setTerminalId(TERMINAL_ID);
        tickerOrderPlace.setOrderNotes("Order -> Pending");
        return tickerOrderPlace;
    }

    private String buildOrderNumber(Long userId, long nowMillis) {
        return TERMINAL_ID + "-" + userId + "-" + nowMillis;
    }

    private String formatYearMonth(long nowMillis) {
        return Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(YEAR_MONTH);
    }

    private String normalizeYearMonth(String yearMonth) {
        if (StringUtils.hasText(yearMonth)) {
            return yearMonth;
        }
        return LocalDate.now().format(YEAR_MONTH);
    }

    private CreateOrderRequest legacyRequest(Long tickerId, int quantity, OrderStrategy strategy) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setTicketItemId(tickerId);
        request.setUserId(1L);
        request.setQuantity(quantity);
        request.setStrategy(strategy);
        request.setIdempotencyKey("legacy-" + tickerId + "-" + quantity + "-" + System.nanoTime());
        return request;
    }

    private void recordOrderMetric(OrderStrategy strategy, boolean success) {
        if (meterRegistry == null || strategy == null) {
            return;
        }
        meterRegistry.counter(
                "flashsale.orders",
                "strategy", strategy.name(),
                "result", success ? "success" : "failed"
        ).increment();
    }

    private void markRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (Exception ignored) {
            log.debug("No active transaction to mark rollback-only");
        }
    }

}
