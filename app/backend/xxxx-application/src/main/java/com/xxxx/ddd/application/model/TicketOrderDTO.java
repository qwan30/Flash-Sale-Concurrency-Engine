package com.xxxx.ddd.application.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TicketOrderDTO {
    private Integer id;
    private Integer userId;
    private String orderNumber;
    private BigDecimal totalAmount;
    private String terminalId;
    private LocalDateTime orderDate;
    private String orderNotes;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}