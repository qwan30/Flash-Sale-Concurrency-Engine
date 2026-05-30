package com.xxxx.ddd.application.service.order;

import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory idempotency cache for local lab requests.
 *
 * <p>This prevents one client retry from reserving stock twice during a running process. It is not a
 * distributed or durable idempotency store.
 */
@Service
public class IdempotencyService {

    private final Map<String, CreateOrderResponse> responses = new ConcurrentHashMap<>();

    public CreateOrderResponse getOrCreate(String key, Supplier<CreateOrderResponse> supplier) {
        return responses.computeIfAbsent(key, ignored -> supplier.get());
    }

    public void clear() {
        responses.clear();
    }
}
