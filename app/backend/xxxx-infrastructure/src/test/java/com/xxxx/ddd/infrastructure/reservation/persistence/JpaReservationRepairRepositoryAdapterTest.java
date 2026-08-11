package com.xxxx.ddd.infrastructure.reservation.persistence;

import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.application.reservation.port.ReservationRepairRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JpaReservationRepairRepositoryAdapterTest {

    private static final long TICKET_ITEM_ID = 42L;
    private static final UUID REPAIR_ID = UUID.fromString("62ed9d04-bf8c-442e-9c1f-0c5a1f0c5e36");

    @Test
    void startClaimsOpenAdmissionWithNewFenceBeforePersistingRepairJournal() {
        EntityManager entityManager = mock(EntityManager.class);
        ReservationStockPort stock = mock(ReservationStockPort.class);
        List<String> sqlCalls = new ArrayList<>();
        List<String> events = new ArrayList<>();
        Query existingRepair = query(List.of(), 0);
        Query stockRow = query(List.<Object>of((Object) new Object[]{10, 8, 7L, "OPEN"}), 0);
        Query activeRepairs = query(List.of(), 0);
        Query buckets = query(List.<Object>of((Object) new Object[]{2, 0}), 0);
        Query admissionTransition = query(List.of(), 1);
        Query repairInsert = query(List.of(), 1);
        when(stock.publishFence(TICKET_ITEM_ID, 8L, "DRAINING")).thenAnswer(invocation -> {
            events.add("redis:DRAINING");
            return "PUBLISHED";
        });
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            sqlCalls.add(sql);
            if (sql.startsWith("UPDATE inventory_stock_account")) {
                events.add("mysql:admission");
            }
            if (sql.contains("WHERE r.repair_id")) {
                return existingRepair;
            }
            if (sql.contains("SELECT initial_quantity") && sql.contains("FOR UPDATE")) {
                return stockRow;
            }
            if (sql.contains("state IN ('STARTED', 'VERIFIED')") && sql.contains("FOR UPDATE")) {
                return activeRepairs;
            }
            if (sql.contains("FROM inventory_reservation")) {
                return buckets;
            }
            if (sql.startsWith("UPDATE inventory_stock_account")) {
                return admissionTransition;
            }
            if (sql.startsWith("INSERT INTO inventory_repair_journal")) {
                return repairInsert;
            }
            throw new AssertionError("unexpected SQL: " + sql);
        });

        Optional<?> result = new JpaReservationRepairRepositoryAdapter(entityManager, stock)
                .start(REPAIR_ID, TICKET_ITEM_ID, "FENCE_STALE");

        assertThat(result).isPresent();
        int activeRepairIndex = indexOf(sqlCalls, "state IN ('STARTED', 'VERIFIED')");
        int admissionTransitionIndex = indexOf(sqlCalls, "admission_state = 'DRAINING'");
        int journalInsertIndex = indexOf(sqlCalls, "INSERT INTO inventory_repair_journal");
        assertThat(activeRepairIndex).isGreaterThan(0);
        assertThat(admissionTransitionIndex).isGreaterThan(activeRepairIndex);
        assertThat(journalInsertIndex).isGreaterThan(admissionTransitionIndex);
        assertThat(sqlCalls.get(admissionTransitionIndex))
                .contains("admission_state = 'DRAINING'")
                .contains("admission_state = 'OPEN'")
                .contains("fence_version = :previousFence")
                .contains("fence_version = :newFence");
        assertThat(events).containsExactly("redis:DRAINING", "mysql:admission");
    }

    @Test
    void startRefusesASecondActiveRepairAfterLockingTheStockAccount() {
        EntityManager entityManager = mock(EntityManager.class);
        ReservationStockPort stock = mock(ReservationStockPort.class);
        List<String> sqlCalls = new ArrayList<>();
        Query existingRepair = query(List.of(), 0);
        Query stockRow = query(List.<Object>of((Object) new Object[]{10, 8, 7L, "OPEN"}), 0);
        Query activeRepairs = query(List.<Object>of((Object) new Object[]{UUID.randomUUID()}), 0);
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            sqlCalls.add(sql);
            if (sql.contains("WHERE r.repair_id")) {
                return existingRepair;
            }
            if (sql.contains("SELECT initial_quantity") && sql.contains("FOR UPDATE")) {
                return stockRow;
            }
            if (sql.contains("state IN ('STARTED', 'VERIFIED')") && sql.contains("FOR UPDATE")) {
                return activeRepairs;
            }
            throw new AssertionError("unexpected SQL: " + sql);
        });

        Optional<?> result = new JpaReservationRepairRepositoryAdapter(entityManager, stock)
                .start(REPAIR_ID, TICKET_ITEM_ID, "FENCE_STALE");

        assertThat(result).isEmpty();
        assertThat(sqlCalls).noneMatch(sql -> sql.startsWith("UPDATE inventory_stock_account"));
        assertThat(sqlCalls).noneMatch(sql -> sql.startsWith("INSERT INTO inventory_repair_journal"));
    }

    @Test
    void startRollsBackRedisFenceWhenTheDurableAdmissionCasIsLost() {
        EntityManager entityManager = mock(EntityManager.class);
        ReservationStockPort stock = mock(ReservationStockPort.class);
        Query existingRepair = query(List.of(), 0);
        Query stockRow = query(List.<Object>of((Object) new Object[]{10, 8, 7L, "OPEN"}), 0);
        Query activeRepairs = query(List.of(), 0);
        Query buckets = query(List.<Object>of((Object) new Object[]{2, 0}), 0);
        Query admissionTransition = query(List.of(), 0);
        when(stock.publishFence(TICKET_ITEM_ID, 8L, "DRAINING")).thenReturn("PUBLISHED");
        when(stock.rollbackFence(TICKET_ITEM_ID, 7L, 8L)).thenReturn("ROLLED_BACK");
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("WHERE r.repair_id")) {
                return existingRepair;
            }
            if (sql.contains("SELECT initial_quantity") && sql.contains("FOR UPDATE")) {
                return stockRow;
            }
            if (sql.contains("state IN ('STARTED', 'VERIFIED')") && sql.contains("FOR UPDATE")) {
                return activeRepairs;
            }
            if (sql.contains("FROM inventory_reservation")) {
                return buckets;
            }
            if (sql.startsWith("UPDATE inventory_stock_account")) {
                return admissionTransition;
            }
            throw new AssertionError("unexpected SQL: " + sql);
        });

        assertThatThrownBy(() -> new JpaReservationRepairRepositoryAdapter(entityManager, stock)
                .start(REPAIR_ID, TICKET_ITEM_ID, "FENCE_STALE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("repair admission claim was lost");
        verify(stock).rollbackFence(TICKET_ITEM_ID, 7L, 8L);
    }

    @Test
    void startRechecksTheRepairAfterAcquiringTheStockLock() {
        EntityManager entityManager = mock(EntityManager.class);
        ReservationStockPort stock = mock(ReservationStockPort.class);
        AtomicInteger repairLookups = new AtomicInteger();
        Query existingBeforeLock = query(List.of(), 0);
        Query existingAfterLock = query(List.<Object>of((Object) new Object[] {
                REPAIR_ID, TICKET_ITEM_ID, 7L, 8L, "STARTED", "FENCE_STALE", 10, 8, 2, 0}), 0);
        Query stockRow = query(List.<Object>of((Object) new Object[]{10, 8, 7L, "DRAINING"}), 0);
        Query activeRepairs = query(List.of(), 0);
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("WHERE r.repair_id")) {
                return repairLookups.incrementAndGet() == 1 ? existingBeforeLock : existingAfterLock;
            }
            if (sql.contains("SELECT initial_quantity") && sql.contains("FOR UPDATE")) {
                return stockRow;
            }
            if (sql.contains("state IN ('STARTED', 'VERIFIED')") && sql.contains("FOR UPDATE")) {
                return activeRepairs;
            }
            throw new AssertionError("unexpected SQL: " + sql);
        });

        assertThat(new JpaReservationRepairRepositoryAdapter(entityManager, stock)
                .start(REPAIR_ID, TICKET_ITEM_ID, "FENCE_STALE"))
                .isPresent()
                .get()
                .extracting("repairId", "newFenceVersion", "state")
                .containsExactly(REPAIR_ID, 8L, ReservationRepairRepository.RepairState.STARTED);
        verifyNoInteractions(stock);
    }

    @Test
    void startAttemptsRollbackAfterAnAmbiguousRedisPublication() {
        EntityManager entityManager = mock(EntityManager.class);
        ReservationStockPort stock = mock(ReservationStockPort.class);
        Query existingRepair = query(List.of(), 0);
        Query stockRow = query(List.<Object>of((Object) new Object[]{10, 8, 7L, "OPEN"}), 0);
        Query activeRepairs = query(List.of(), 0);
        when(stock.rollbackFence(TICKET_ITEM_ID, 7L, 8L)).thenReturn("REPLAYED");
        doThrow(new IllegalStateException("redis timeout"))
                .when(stock).publishFence(TICKET_ITEM_ID, 8L, "DRAINING");
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("WHERE r.repair_id")) {
                return existingRepair;
            }
            if (sql.contains("SELECT initial_quantity") && sql.contains("FOR UPDATE")) {
                return stockRow;
            }
            if (sql.contains("state IN ('STARTED', 'VERIFIED')") && sql.contains("FOR UPDATE")) {
                return activeRepairs;
            }
            throw new AssertionError("unexpected SQL: " + sql);
        });

        assertThatThrownBy(() -> new JpaReservationRepairRepositoryAdapter(entityManager, stock)
                .start(REPAIR_ID, TICKET_ITEM_ID, "FENCE_STALE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis timeout");
        verify(stock).rollbackFence(TICKET_ITEM_ID, 7L, 8L);
    }

    @Test
    void closeCasTransitionsOnlyTheCurrentRepairFenceFromDrainingToClosed() {
        EntityManager entityManager = mock(EntityManager.class);
        ReservationStockPort stock = mock(ReservationStockPort.class);
        List<String> sqlCalls = new ArrayList<>();
        Query repair = query(List.<Object>of((Object) new Object[]{
                REPAIR_ID, TICKET_ITEM_ID, 7L, 8L, "STARTED", "FENCE_STALE", 10, 8, 2, 0}), 0);
        Query close = query(List.of(), 1);
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            sqlCalls.add(sql);
            if (sql.contains("WHERE r.repair_id")) {
                return repair;
            }
            if (sql.startsWith("UPDATE inventory_stock_account")) {
                return close;
            }
            throw new AssertionError("unexpected SQL: " + sql);
        });

        assertThat(new JpaReservationRepairRepositoryAdapter(entityManager, stock).close(REPAIR_ID)).isTrue();
        assertThat(sqlCalls).anyMatch(sql -> sql.contains("admission_state = 'CLOSED'")
                && sql.contains("admission_state = 'DRAINING'")
                && sql.contains("fence_version = :newFence"));
    }

    private static Query query(List<?> rows, int updateCount) {
        Query query = mock(Query.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        when(query.executeUpdate()).thenReturn(updateCount);
        return query;
    }

    private static int indexOf(List<String> sqlCalls, String fragment) {
        for (int index = 0; index < sqlCalls.size(); index++) {
            if (sqlCalls.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }
}
