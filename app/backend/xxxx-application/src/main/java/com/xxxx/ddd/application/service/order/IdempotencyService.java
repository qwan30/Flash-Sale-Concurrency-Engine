package com.xxxx.ddd.application.service.order;

import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
