package com.xxxx.ddd.application.service.order.cache;

import com.xxxx.ddd.application.model.cache.TicketDetailCache;
import com.xxxx.ddd.application.port.cache.CacheStore;
import com.xxxx.ddd.application.service.ticket.cache.TicketDetailCacheServiceRefactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class StockOrderCacheService {

    @Autowired
    private TicketDetailCacheServiceRefactor ticketDetailCacheServiceRefactor;

    @Autowired
    private CacheStore cacheStore;

    @Deprecated
    public boolean AddStockAvailableToCache(Long ticketId) {
        return addStockAvailableToCache(ticketId);
    }

    public boolean addStockAvailableToCache(Long ticketId) {
        // That's remember check validation(*)
        if(ticketId == null) {
            return false;
        }
        // get stock_available from mysql
        TicketDetailCache ticketDetailCache = ticketDetailCacheServiceRefactor.getTicketDetail(ticketId, null);
        if(ticketDetailCache == null) {
            return false;
        }
        String keyStockItemCache = getKeyStockItemCache(ticketId);
        log.info("get->getKeyStockItemCache() | {}, {}, {}", ticketId, keyStockItemCache,
                ticketDetailCache.getTicketDetail().getStockAvailable());
        // stockAvailable = ticketDetailCache.getTicketDetail().getStockAvailable();
        cacheStore.setInt(keyStockItemCache, ticketDetailCache.getTicketDetail().getStockAvailable());
        return true;
    }

    public void setStockCache(Long ticketId, int stock) {
        cacheStore.setInt(getKeyStockItemCache(ticketId), stock);
    }

    public int getStockCache(Long ticketId) {
        Integer stock = cacheStore.getIntOrNull(getKeyStockItemCache(ticketId));
        return stock == null ? -1 : stock;
    }

    public void restoreStockCache(Long ticketId, int quantity) {
        cacheStore.increment(getKeyStockItemCache(ticketId), quantity);
    }

    // decreaseStockCache
    public int decreaseStockCache(Long ticketId, Integer quantity) {
        // 1. Get Stock Available
        String keyStockNormal = getKeyStockItemCache(ticketId);
        int stockAvailable = cacheStore.getInt(keyStockNormal); // 100
        log.info("stockAvailable Normal: {}, {}, {} ", keyStockNormal, stockAvailable, String.valueOf(stockAvailable - quantity));
        // 2. Decrease Stock

        if(stockAvailable >= quantity){ // 100 > 1 = 99
            cacheStore.setInt(keyStockNormal, stockAvailable - quantity); // 99
            log.info("stockAvailable racing...: {}", stockAvailable - quantity);
            return 1;
        }
        return 0; // stockAvailable = 0 , quantity = 1
    }

    public int decreaseStockCacheByLUA(Long ticketId, Integer quantity) {
        return (int) decreaseStockCacheByLuaReturningRemaining(ticketId, quantity);
    }

    public long decreaseStockCacheByLuaReturningRemaining(Long ticketId, Integer quantity) {
        // 1. Get Stock Available
        String keyStockLUA = getKeyStockItemCache(ticketId);
        return cacheStore.decreaseIntByLuaReturningRemaining(keyStockLUA, quantity);
    }


    private String getKeyStockItemCache(Long ticketId) {
        return "TICKET:"+ ticketId + ":STOCK";
    }

    private String getKeyStockCacheLUA(Long ticketId){
        return "LUA:TICKET:" + ticketId + ":STOCK";
    }
}
