package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.TicketDetailDTO;
import com.xxxx.ddd.application.service.ticket.TicketDetailAppService;
import com.xxxx.ddd.controller.model.enums.ResultUtil;
import com.xxxx.ddd.controller.model.vo.ResultMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TicketQueryController {

    @Autowired
    private TicketDetailAppService ticketDetailAppService;

    @GetMapping("/tickets/{ticketItemId}")
    public ResultMessage<TicketDetailDTO> getTicket(@PathVariable("ticketItemId") Long ticketItemId) {
        return ResultUtil.data(ticketDetailAppService.getTicketDetailById(ticketItemId, null));
    }
}
