package com.xxxx.ddd.application.service.ticket.impl;

import com.xxxx.ddd.application.mapper.TicketDetailMapper;
import com.xxxx.ddd.application.model.TicketDetailDTO;
import com.xxxx.ddd.application.model.cache.TicketDetailCache;
import com.xxxx.ddd.application.service.ticket.TicketDetailAppService;
import com.xxxx.ddd.application.service.ticket.cache.TicketDetailCacheService;
import com.xxxx.ddd.application.service.ticket.cache.TicketDetailCacheServiceRefactor;
import com.xxxx.ddd.domain.model.entity.TicketDetail;
import com.xxxx.ddd.domain.service.TicketDetailDomainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Reads ticket fixture details through the cache-backed query path used by stock warmup.
 */
@Service
@Slf4j
public class TicketDetailAppServiceImpl implements TicketDetailAppService {

    @Autowired
    private TicketDetailDomainService ticketDetailDomainService;

    @Autowired
    private TicketDetailCacheService ticketDetailCacheService;

    @Autowired
    private TicketDetailCacheServiceRefactor ticketDetailCacheServiceRefactor;

    @Override
    public TicketDetailDTO getTicketDetailById(Long ticketId, Long version) {
//        log.info("Implement Application : {}, {}: ", ticketId, version);
        TicketDetailCache ticketDetailCache = ticketDetailCacheServiceRefactor.getTicketDetail(ticketId, version);
        // Return the cache version with the DTO so callers can reason about stale reads.
        TicketDetailDTO ticketDetailDTO = TicketDetailMapper.mapperToTicketDetailDTO(ticketDetailCache.getTicketDetail());
        ticketDetailDTO.setVersion(ticketDetailCache.getVersion());
        return ticketDetailDTO;
    }

    @Override
    public boolean orderTicketByUser(Long ticketId) {
        return ticketDetailCacheServiceRefactor.orderTicketByUser(ticketId);
    }


}
