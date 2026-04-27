package com.xxxx.ddd.infrastructure.persistence.repository;

import com.xxxx.ddd.domain.respository.TickerOrderRepository;
import com.xxxx.ddd.infrastructure.persistence.mapper.TicketOrderJPAMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TickerOrderRepositoryImpl implements TickerOrderRepository {

    @Autowired
    private TicketOrderJPAMapper ticketOrderJPAMapper;

    @Override
    public boolean decreaseStockUnsafe(Long tickerId, int quantity) {
        log.info("Run test:decreaseStockUnsafe with: | {}, {} ", tickerId, quantity);
        return ticketOrderJPAMapper.decreaseStockLevel0(tickerId, quantity) > 0;
    }

    @Override
    public boolean decreaseStockLevel1(Long tickerId, int quantity) {
        log.info("Run test:decreaseStockLevel1 with: | {}, {} ", tickerId, quantity);
        return ticketOrderJPAMapper.decreaseStockLevel1(tickerId, quantity) > 0;
    }

    @Override
    public boolean decreaseStockLevel2(Long tickerId, int quantity) {
        return false;
    }

    @Override
    public boolean decreaseStockLevel3CAS(Long tickerId, int oldStockAvailable, int quantity) {
        log.info("Run test:decreaseStockLevel3CAS with: | {}, {}, {} ", tickerId, oldStockAvailable, quantity);
        return ticketOrderJPAMapper.decreaseStockLevel3CAS(tickerId, oldStockAvailable, quantity) > 0;
    }

    @Override
    public int getStockAvailable(Long ticketId) {
        return ticketOrderJPAMapper.getStockAvailable(ticketId);
    }

    @Override
    public boolean resetStock(Long ticketId, int stock) {
        return ticketOrderJPAMapper.resetStock(ticketId, stock) > 0;
    }
}
