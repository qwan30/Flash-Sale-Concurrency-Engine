package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.TicketDetailDTO;
import com.xxxx.ddd.application.service.ticket.TicketDetailAppService;
import com.xxxx.ddd.controller.model.enums.ResultUtil;
import com.xxxx.ddd.controller.model.vo.ResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket-detail fixture endpoints used by the catalog and legacy demo screens.
 */
@RestController
@RequestMapping("/ticket")
@Slf4j
public class TicketDetailController {

    @Autowired
    private TicketDetailAppService ticketDetailAppService;

    @GetMapping("/ping/java")
    public ResponseEntity<Object> ping() throws InterruptedException {
        // Simulate a slow backend call for local latency experiments.
        Thread.sleep(1000);

        return ResponseEntity.status(HttpStatus.OK)
                .body(new Response("OK"));
    }

    public static class Response {
        private String status;

        public Response(String status) {
            this.status = status;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /**
     * Reads ticket-detail fixture data for the selected event.
     */
    @GetMapping("/{ticketId}/detail/{detailId}")
    public ResultMessage<TicketDetailDTO> getTicketDetail(
            @PathVariable("ticketId") Long ticketId,
            @PathVariable("detailId") Long detailId,
            @RequestParam(name = "version", required = false) Long version
    ) {
        return ResultUtil.data(ticketDetailAppService.getTicketDetailById(detailId, version));
    }

    /**
     * Deprecated direct detail-order route retained for the original demo flow.
     */
    @GetMapping("/{ticketId}/detail/{detailId}/order")
    @Deprecated
    public boolean orderTicketByUser(
            @PathVariable("ticketId") Long ticketId,
            @PathVariable("detailId") Long detailId
    ) {
        return ticketDetailAppService.orderTicketByUser(detailId);
    }
}
