package com.ipgo.server.support;

import org.springframework.jdbc.core.JdbcTemplate;

// 테스트 DB는 테스트 클래스끼리 공유된다. 업로드가 만든 행을 지워야 다른 테스트의 회원 삭제가 막히지 않는다.
public final class UploadTestDatabase {
    private UploadTestDatabase() { }

    public static long newUser(JdbcTemplate jdbc, String email) {
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    public static void receiptMatchingSchema(JdbcTemplate jdbc) {
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                jdbc.execute("CREATE TABLE IF NOT EXISTS insight_monthly_usage(month_key DATE PRIMARY KEY,reserved_calls INTEGER NOT NULL,updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
            }
        } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)='ai_explanation_calls'", Integer.class) == 0) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migration/V18__store_insight_call_history.sql"))
                    .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        }

        // 기존 harness의 V11 뒤에 portable 정본 V16을 적용한다. V12~15는 별도 PG 전용 검사 대상이다.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'receipt_matching_events'", Integer.class) == 0) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migration/V16__create_receipt_matching.sql"))
                    .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'receipt_correction_decisions'", Integer.class) == 0) {
            try {
                var resource = new org.springframework.core.io.ClassPathResource("db/migration/V20__receipt_correction_decisions.sql");
                String sql = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                        .split("-- PostgreSQL role permissions")[0];
                new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                        new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
    }

    public static void storageCostSchema(JdbcTemplate jdbc) {
        // 기존 harness의 V11 뒤에 portable V19를 적용한다(#111). 테스트 DB는 클래스끼리 공유되므로 한 번만 적용한다.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'product_boxes'", Integer.class) == 0) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migration/V19__create_storage_cost.sql"))
                    .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        }
    }

    public static void plannedQuantitySchema(JdbcTemplate jdbc) {
        // 기존 harness의 V11 뒤에 portable V21을 적용한다(#119). 테스트 DB는 클래스끼리 공유되므로 한 번만 적용한다.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'product_planned_quantities'", Integer.class) == 0) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migration/V21__create_planned_quantities.sql"))
                    .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        }
    }

    public static void expirySchema(JdbcTemplate jdbc) {
        // 기존 harness의 V11 뒤에 portable V23을 적용한다(#168). 테스트 DB는 클래스끼리 공유되므로 한 번만 적용한다.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) = 'inventory_units' AND LOWER(column_name) = 'expiry_date'", Integer.class) == 0) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migration/V23__add_inventory_expiry_dates.sql"))
                    .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        }
    }

    public static void withdrawalSchema(JdbcTemplate jdbc) {
        // #172 탈퇴는 결제 원장(V17)의 구독을 멈추고 users.withdrawn_at(V24)을 쓴다. H2 harness에만 portable 부분을 한 번 적용한다.
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            if (connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) return;
        } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'billing_subscriptions'", Integer.class) == 0) {
            try {
                String sql = new org.springframework.core.io.ClassPathResource("db/migration/V17__create_billing_ledger.sql")
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8).split("-- PostgreSQL 전용")[0];
                for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) = 'users' AND LOWER(column_name) = 'withdrawn_at'", Integer.class) == 0) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migration/V24__add_user_withdrawn_at.sql"))
                    .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'billing_refunds'", Integer.class) == 0) {
            try (var stream = new org.springframework.core.io.ClassPathResource("db/migration/V25__create_billing_refunds.sql").getInputStream()) {
                String sql = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("-- PostgreSQL 전용")[0];
                for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }

    }

    public static void clear(JdbcTemplate jdbc) {
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                jdbc.execute("SELECT ipgo_test_clear_receipt_history()");
            } else {
                jdbc.update("DELETE FROM insight_monthly_usage");
                jdbc.update("DELETE FROM receipt_allocation_history");
                jdbc.update("DELETE FROM receipt_correction_decisions");
            }
        } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        jdbc.update("DELETE FROM receipt_matching_reports");
        jdbc.update("DELETE FROM receipt_matching_allocations");
        jdbc.update("DELETE FROM receipt_matching_events");
        jdbc.update("DELETE FROM ai_explanations");
        jdbc.update("DELETE FROM backtest_metrics");
        jdbc.update("DELETE FROM scenario_results");
        jdbc.update("DELETE FROM run_source_files");
        jdbc.update("DELETE FROM calculation_results");
        jdbc.update("DELETE FROM calculation_runs");
        jdbc.update("DELETE FROM product_daily_stocks");
        jdbc.update("DELETE FROM daily_stock_records");
        jdbc.update("DELETE FROM upload_files");
        jdbc.update("DELETE FROM planned_receipts");
        jdbc.update("DELETE FROM replenishment_settings");
        jdbc.update("DELETE FROM inventory_units");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }
}
