package com.xxxx.ddd.application.service.ticket;

import com.xxxx.ddd.application.model.TicketDetailDTO;
import com.xxxx.ddd.domain.model.entity.TicketDetail;

public interface TicketDetailAppService {

    TicketDetailDTO getTicketDetailById(Long ticketId, Long version); // should convert to TickDetailDTO by Application Module
    // order ticket
    boolean orderTicketByUser(Long ticketId);
}
