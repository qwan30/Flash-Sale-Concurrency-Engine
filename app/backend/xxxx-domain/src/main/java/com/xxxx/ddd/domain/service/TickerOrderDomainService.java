package com.xxxx.ddd.domain.service;

public interface TickerOrderDomainService {

    boolean decreaseStockUnsafe(Long tickerId, int quantity);
    boolean decreaseStockLevel1(Long tickerId, int quantity);
    boolean decreaseStockLevel2(Long tickerId, int quantity);
    boolean decreaseStockLevel3CAS(Long tickerId, int oldStockAvailable, int quantity);

    // get stockAvailable
    int getStockAvailable(Long ticketId);

    boolean resetStock(Long ticketId, int stock);
}
