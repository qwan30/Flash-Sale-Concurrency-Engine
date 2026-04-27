package com.xxxx.ddd.infrastructure.persistence.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDeductionInfrasRepositoryImplTest {

    @Test
    void resolvesSafeMonthlyOrderTableName() {
        assertThat(OrderDeductionInfrasRepositoryImpl.resolveOrderTableName("202604"))
                .isEqualTo("ticket_order_202604");
    }

    @Test
    void rejectsUnsafeMonthlyOrderTableName() {
        assertThatThrownBy(() -> OrderDeductionInfrasRepositoryImpl.resolveOrderTableName("2026xx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyyMM");
    }
}
