package com.xxxx.ddd.domain.service.impl;

import com.xxxx.ddd.domain.model.entity.TicketDetail;
import com.xxxx.ddd.domain.respository.TicketDetailRepository;
import com.xxxx.ddd.domain.service.TicketDetailDomainService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TicketDetailDomainServiceImpl implements TicketDetailDomainService {
    // Call repository in domain
//    private static final Logger log = LoggerFactory.getLogger(TicketDetailDomainServiceImpl.class);
    @Autowired
    private TicketDetailRepository ticketDetailRepository;

    @Override
    public TicketDetail getTicketDetailById(Long ticketId) {
//        log.info("Implement Domain : {}", ticketId);
        return ticketDetailRepository.findById(ticketId).orElse(null);
    }
}
