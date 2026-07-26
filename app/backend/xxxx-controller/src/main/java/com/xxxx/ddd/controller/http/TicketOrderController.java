package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.TicketOrderDTO;
import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import com.xxxx.ddd.controller.model.enums.ResultUtil;
import com.xxxx.ddd.controller.model.vo.ResultMessage;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public order API used by the lab dashboard and benchmark clients.
 *
 * <p>The modern flow is {@code POST /orders}. Deprecated GET endpoints stay available only for
 * older benchmark plans that still call the original demo routes.
 */
@RestController
@Slf4j
public class TicketOrderController {

    @Autowired
    private TicketOrderAppService ticketOrderAppService;

    /**
     * Creates an order through the strategy selected in the request body.
     *
     * <p>The rate limiter is the outermost admission control: it sheds load before a request can
     * reach stock deduction, so a burst beyond the configured ceiling is rejected with 429 rather
     * than queuing on Redis or the database.
     */
    @PostMapping("/orders")
    @RateLimiter(name = "orderApi", fallbackMethod = "createOrderRateLimited")
    public ResponseEntity<ResultMessage<CreateOrderResponse>> createOrder(@RequestBody CreateOrderRequest request) {
        CreateOrderResponse response = ticketOrderAppService.createOrder(request);
        return ResponseEntity.status(statusFor(response)).body(message(response));
    }

    /**
     * Fallback invoked when the order API rate limit is exceeded.
     *
     * <p>Returns 429 so clients can distinguish shed load from a sell-out rejection (409) or a
     * malformed request (400).
     */
    public ResponseEntity<ResultMessage<CreateOrderResponse>> createOrderRateLimited(
            CreateOrderRequest request,
            Throwable throwable
    ) {
        log.warn("Order API rate limit exceeded | ticketItemId={}, userId={}",
                request == null ? null : request.getTicketItemId(),
                request == null ? null : request.getUserId());

        CreateOrderResponse response = CreateOrderResponse.failure(
                request, "RATE_LIMITED", "Order API rate limit exceeded, retry shortly");

        ResultMessage<CreateOrderResponse> body = new ResultMessage<>();
        body.setSuccess(false);
        body.setCode(HttpStatus.TOO_MANY_REQUESTS.value());
        body.setMessage(response.getMessage());
        body.setResult(response);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @GetMapping("/orders")
    public ResultMessage<List<TicketOrderDTO>> listOrders(
            @RequestParam("userId") Long userId,
            @RequestParam("yearMonth") String yearMonth
    ) {
        log.info("Controller:->listOrders | {}, {}", userId, yearMonth);
        return ResultUtil.data(ticketOrderAppService.findAllByUser(yearMonth, userId));
    }

    @GetMapping("/orders/{orderNumber}")
    public ResultMessage<TicketOrderDTO> getOrder(@PathVariable("orderNumber") String orderNumber) {
        log.info("Controller:->getOrder | {}", orderNumber);
        return ResultUtil.data(ticketOrderAppService.findByOrderNumber(null, orderNumber));
    }

    /**
     * Deprecated demo endpoint kept so old benchmark plans continue to run while the lab migrates to POST /orders.
     */
    @Deprecated
    @GetMapping("/order/{ticketId}/{quantity}/order")
    public boolean orderTicketByLevel(
            @PathVariable("ticketId") Long ticketId,
            @PathVariable("quantity") int quantity
    ) {
        log.info("Controller:->orderTicketByLevel | {}, {}", ticketId, quantity);
        return ticketOrderAppService.decreaseStockLevel1(ticketId, quantity);
    }

    /**
     * Deprecated demo endpoint kept so old benchmark plans continue to run while the lab migrates to POST /orders.
     */
    @Deprecated
    @GetMapping("/order/{ticketId}/{quantity}/cas")
    public boolean orderTicketByLevel3(
            @PathVariable("ticketId") Long ticketId,
            @PathVariable("quantity") int quantity
    ) {
        log.info("Controller:->orderTicketByLevel3 | {}, {}", ticketId, quantity);
        return ticketOrderAppService.decreaseStockLevel3CAS(ticketId, quantity);
    }

    @Deprecated
    @GetMapping("/order/{userId}/list")
    public ResultMessage<List<TicketOrderDTO>> getListOrderByUser(
            @PathVariable("userId") Long userId,
            @RequestParam("ntable") String ntable
    ) {
        log.info("Controller:->getListOrderByUser | {}, {}", userId, ntable);
        return ResultUtil.data(ticketOrderAppService.findAll(ntable));
    }

    @Deprecated
    @GetMapping("/order/{userId}/{orderNumber}")
    public ResultMessage<TicketOrderDTO> getOrderByUser(
            @PathVariable("userId") Long userId,
            @PathVariable("orderNumber") String orderNumber
    ) {
        log.info("Controller:->getOrderByUser | {}, {}", userId, orderNumber);
        return ResultUtil.data(ticketOrderAppService.findByOrderNumber(null, orderNumber));
    }

    private HttpStatus statusFor(CreateOrderResponse response) {
        // Business rejections use 409 so benchmark clients can distinguish validation from sell-out.
        if ("INVALID_REQUEST".equals(response.getCode())) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.OK;
    }

    private ResultMessage<CreateOrderResponse> message(CreateOrderResponse response) {
        ResultMessage<CreateOrderResponse> message = new ResultMessage<>();
        message.setSuccess(response.isSuccess());
        message.setCode(response.isSuccess() ? 200 : "INVALID_REQUEST".equals(response.getCode()) ? 400 : 409);
        message.setMessage(response.getMessage());
        message.setResult(response);
        return message;
    }
}
