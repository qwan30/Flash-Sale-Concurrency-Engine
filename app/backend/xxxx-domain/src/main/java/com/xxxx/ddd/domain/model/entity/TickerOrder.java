package com.xxxx.ddd.domain.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TickerOrder {

    private Integer id;

    private String orderNumber;

    private Integer userId;

    private BigDecimal totalAmount;

    private String terminalId;

    private LocalDateTime orderDate;

    private String orderNotes;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}
