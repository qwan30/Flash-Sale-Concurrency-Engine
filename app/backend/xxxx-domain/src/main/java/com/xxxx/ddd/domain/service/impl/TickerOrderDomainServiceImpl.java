package com.xxxx.ddd.domain.service.impl;

import com.xxxx.ddd.domain.respository.TickerOrderRepository;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TickerOrderDomainServiceImpl implements TickerOrderDomainService {

    @Autowired
    private TickerOrderRepository tickerOrderRepository;

    @Override
    public boolean decreaseStockUnsafe(Long tickerId, int quantity) {
        return tickerOrderRepository.decreaseStockUnsafe(tickerId, quantity);
    }

    @Override
    public boolean decreaseStockLevel1(Long tickerId, int quantity) {
        return tickerOrderRepository.decreaseStockLevel1(tickerId, quantity);
    }

    @Override
    public boolean decreaseStockLevel2(Long tickerId, int quantity) {
        return false;
    }

    @Override
    public boolean decreaseStockLevel3CAS(Long tickerId, int oldStockAvailable, int quantity) {
        return tickerOrderRepository.decreaseStockLevel3CAS(tickerId, oldStockAvailable, quantity);
    }

    @Override
    public int getStockAvailable(Long ticketId) {
        return tickerOrderRepository.getStockAvailable(ticketId);
    }

    @Override
    public boolean resetStock(Long ticketId, int stock) {
        return tickerOrderRepository.resetStock(ticketId, stock);
    }
}
