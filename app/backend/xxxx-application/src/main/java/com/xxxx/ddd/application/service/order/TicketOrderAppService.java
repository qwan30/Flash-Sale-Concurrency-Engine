package com.xxxx.ddd.application.service.order;

import com.xxxx.ddd.application.model.TicketOrderDTO;
import com.xxxx.ddd.application.model.order.BenchmarkResetRequest;
import com.xxxx.ddd.application.model.order.BenchmarkResetResponse;
import com.xxxx.ddd.application.model.order.ConsistencySnapshot;
import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.domain.model.entity.TickerOrder;

import java.util.List;

public interface TicketOrderAppService {

    CreateOrderResponse createOrder(CreateOrderRequest request);

    CreateOrderResponse warmupStock(Long ticketItemId);

    BenchmarkResetResponse resetBenchmark(BenchmarkResetRequest request);

    ConsistencySnapshot getConsistency(Long ticketItemId, String yearMonth);

    boolean decreaseStockLevel1(Long tickerId, int quantity);
    boolean decreaseStockLevel2(Long tickerId, int quantity);
    boolean decreaseStockLevel3CAS(Long tickerId, int quantity);

    int getStockAvailable(Long ticketId);

    // order..
    List<TicketOrderDTO> findAll(String yearMonth);
    List<TicketOrderDTO> findAllByUser(String yearMonth, Long userId);
    boolean insertOrder(String yearMonth, TickerOrder tickerOrder);
    TicketOrderDTO findByOrderNumber(String yearMonth, String orderNumber);
}
