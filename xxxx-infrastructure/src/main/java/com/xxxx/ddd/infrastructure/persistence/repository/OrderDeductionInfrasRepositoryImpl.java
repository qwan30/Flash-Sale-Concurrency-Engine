package com.xxxx.ddd.infrastructure.persistence.repository;

import com.xxxx.ddd.domain.model.entity.TickerOrder;
import com.xxxx.ddd.domain.respository.OrderDeductionRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class OrderDeductionInfrasRepositoryImpl implements OrderDeductionRepository {

    @Autowired
    private EntityManager entityManager;
    private static final String TABLE_PREFIX = "ticket_order_";

    public static String resolveOrderTableName(String monthOrder) {
        if (monthOrder == null || !monthOrder.matches("\\d{6}")) {
            throw new IllegalArgumentException("yearMonth must match yyyyMM");
        }
        return TABLE_PREFIX + monthOrder;
    }

    private String getTableName(String monthOrder) {
        return resolveOrderTableName(monthOrder);
    }

    @Override
    @Transactional
    public void ensureMonthlyOrderTable(String yearMonth) {
        String tableName = getTableName(yearMonth);
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                "id INT(8) NOT NULL AUTO_INCREMENT COMMENT 'Unique ticket sales ID', " +
                "user_id INT(8) NOT NULL COMMENT 'userId', " +
                "order_number VARCHAR(50) NOT NULL COMMENT 'Unique order number', " +
                "total_amount DECIMAL(10,3) NOT NULL COMMENT 'Total payment amount', " +
                "terminal_id VARCHAR(20) NOT NULL COMMENT 'ID of the sales terminal', " +
                "order_date TIMESTAMP NOT NULL COMMENT 'Date and time of the ticket purchase', " +
                "order_notes VARCHAR(100) NULL DEFAULT 'None' COMMENT 'Additional notes for the order', " +
                "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Timestamp of the last update', " +
                "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp', " +
                "PRIMARY KEY (id) USING BTREE, " +
                "UNIQUE KEY order_number (order_number), " +
                "KEY order_date (order_date), " +
                "KEY index_usr_id (user_id)" +
                ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'order table'";
        entityManager.createNativeQuery(sql).executeUpdate();
    }

    @Override
    @Transactional
    public void insertOrder(String yearMonth, TickerOrder order) {
        ensureMonthlyOrderTable(yearMonth);
        String tableName = getTableName(yearMonth);
        String sql = "INSERT INTO " + tableName + " (order_number, user_id, total_amount, terminal_id, order_date, order_notes, updated_at, created_at) " +
                "VALUES (:orderNumber, :userId, :totalAmount, :terminalId, :orderDate, :orderNotes, :updatedAt, :createdAt)";

        // pls: rememeber that: add log by time
        LocalDateTime now = LocalDateTime.now();
        order.setOrderDate(now);
        order.setUpdatedAt(now);
        order.setCreatedAt(now);

        entityManager.createNativeQuery(sql)
                .setParameter("orderNumber", order.getOrderNumber())
                .setParameter("userId", order.getUserId())
                .setParameter("totalAmount", order.getTotalAmount())
                .setParameter("terminalId", order.getTerminalId())
                .setParameter("orderDate", order.getOrderDate())
                .setParameter("orderNotes", order.getOrderNotes())
                .setParameter("updatedAt", order.getUpdatedAt())
                .setParameter("createdAt", order.getCreatedAt())
                .executeUpdate();
    }

    @Override
    public List<Object[]> findAll(String yearMonth) {
        ensureMonthlyOrderTable(yearMonth);
        String tableName = getTableName(yearMonth);
        String sql = "SELECT * FROM " + tableName;
        return entityManager.createNativeQuery(sql).getResultList();
    }

    @Override
    public List<Object[]> findAllByUser(String yearMonth, Long userId) {
        ensureMonthlyOrderTable(yearMonth);
        String tableName = getTableName(yearMonth);
        String sql = "SELECT * FROM " + tableName + " WHERE user_id = :userId";
        return entityManager.createNativeQuery(sql)
                .setParameter("userId", userId)
                .getResultList();
    }

    @Override
    public Object[] findByOrderNumber(String yearMonth, String orderNumber) {
        ensureMonthlyOrderTable(yearMonth);
        String tableName = getTableName(yearMonth);
        String sql = "SELECT * FROM " + tableName + " WHERE order_number = :orderNumber";
        List<Object[]> resultList = entityManager.createNativeQuery(sql)
                .setParameter("orderNumber", orderNumber)
                .getResultList();
        return resultList.isEmpty() ? null : resultList.get(0);
    }

    @Override
    public List<Object[]> findByDateRange(String yearMonth, LocalDateTime startDate, LocalDateTime endDate) {
        ensureMonthlyOrderTable(yearMonth);
        String tableName = getTableName(yearMonth);
        String sql = "SELECT * FROM " + tableName + " WHERE order_date BETWEEN :startDate AND :endDate";
        return entityManager.createNativeQuery(sql)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .getResultList();
    }

    @Override
    @Transactional
    public void clearOrders(String yearMonth) {
        ensureMonthlyOrderTable(yearMonth);
        String tableName = getTableName(yearMonth);
        entityManager.createNativeQuery("DELETE FROM " + tableName).executeUpdate();
    }

    @Override
    public long countOrders(String yearMonth) {
        ensureMonthlyOrderTable(yearMonth);
        String tableName = getTableName(yearMonth);
        Number count = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM " + tableName).getSingleResult();
        return count.longValue();
    }
}
