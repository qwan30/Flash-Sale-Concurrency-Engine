package com.xxxx.ddd.application.service.order.support;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class OrderDateSupport {

    public static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private OrderDateSupport() {
    }

    public static String formatYearMonth(long nowMillis) {
        return Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(YEAR_MONTH);
    }

    public static String normalizeYearMonth(String yearMonth) {
        if (StringUtils.hasText(yearMonth)) {
            return yearMonth;
        }
        return LocalDate.now().format(YEAR_MONTH);
    }
}
