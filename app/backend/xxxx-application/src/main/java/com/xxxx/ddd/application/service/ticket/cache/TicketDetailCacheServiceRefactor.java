package com.xxxx.ddd.application.service.ticket.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.xxxx.ddd.application.model.cache.TicketDetailCache;
import com.xxxx.ddd.application.port.cache.ApplicationDistributedLock;
import com.xxxx.ddd.application.port.cache.CacheStore;
import com.xxxx.ddd.application.port.cache.DistributedLockService;
import com.xxxx.ddd.domain.model.entity.TicketDetail;
import com.xxxx.ddd.domain.service.TicketDetailDomainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TicketDetailCacheServiceRefactor {

    @Autowired
    private DistributedLockService distributedLockService;

    @Autowired
    private CacheStore cacheStore;

    @Autowired
    private TicketDetailDomainService ticketDetailDomainService;

    private static final Cache<Long, TicketDetailCache> ticketDetailLocalCache = CacheBuilder.newBuilder()
            .initialCapacity(10)
            .concurrencyLevel(12)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    public boolean orderTicketByUser(Long ticketId) {
        ticketDetailLocalCache.invalidate(ticketId);
        cacheStore.delete(genEventItemKey(ticketId));
        return true;
    }

    public TicketDetailCache getTicketDetail(Long ticketId, Long version) {
        if (ticketId == null) {
            return null;
        }
        TicketDetailCache ticketDetailCache = getTicketDetailLocalCache(ticketId);
        if (ticketDetailCache != null && (version == null || version <= ticketDetailCache.getVersion())) {
            log.info("GET TICKET FROM LOCAL CACHE: ticketId={}, versionUser={}, versionLocal={}",
                    ticketId, version, ticketDetailCache.getVersion());
            return ticketDetailCache;
        }
        return getTicketDetailDistributedCache(ticketId);
    }

    public TicketDetailCache getTicketDetailDatabase(Long ticketId) {
        ApplicationDistributedLock locker = distributedLockService.getDistributedLock(genEventItemKeyLock(ticketId));
        boolean locked = false;
        try {
            locked = locker.tryLock(1, 5, TimeUnit.SECONDS);
            if (!locked) {
                return loadTicketDetailFromDatabase(ticketId);
            }

            TicketDetailCache ticketDetailCache = cacheStore.getObject(genEventItemKey(ticketId), TicketDetailCache.class);
            if (ticketDetailCache != null) {
                return ticketDetailCache;
            }

            ticketDetailCache = loadTicketDetailFromDatabase(ticketId);
            cacheStore.setObject(genEventItemKey(ticketId), ticketDetailCache);
            return ticketDetailCache;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (locked) {
                locker.unlock();
            }
        }
    }

    public TicketDetailCache getTicketDetailDistributedCache(Long ticketId) {
        TicketDetailCache ticketDetailCache = cacheStore.getObject(genEventItemKey(ticketId), TicketDetailCache.class);
        if (ticketDetailCache == null) {
            log.info("GET TICKET FROM DISTRIBUTED LOCK: ticketId={}", ticketId);
            ticketDetailCache = getTicketDetailDatabase(ticketId);
        }
        if (ticketDetailCache != null) {
            ticketDetailLocalCache.put(ticketId, ticketDetailCache);
        }
        log.info("GET TICKET FROM DISTRIBUTED CACHE: ticketId={}, stockAvailable={}",
                ticketId, ticketDetailCache == null ? null : ticketDetailCache.getTicketDetail().getStockAvailable());
        return ticketDetailCache;
    }

    public TicketDetailCache getTicketDetailLocalCache(Long ticketId) {
        return ticketDetailLocalCache.getIfPresent(ticketId);
    }

    private TicketDetailCache loadTicketDetailFromDatabase(Long ticketId) {
        TicketDetail ticketDetail = ticketDetailDomainService.getTicketDetailById(ticketId);
        return new TicketDetailCache().withClone(ticketDetail).withVersion(System.currentTimeMillis());
    }

    private String genEventItemKey(Long ticketId) {
        return "PRO_TICKET:ITEM:" + ticketId;
    }

    private String genEventItemKeyLock(Long ticketId) {
        return "PRO_LOCK_KEY_ITEM" + ticketId;
    }
}
