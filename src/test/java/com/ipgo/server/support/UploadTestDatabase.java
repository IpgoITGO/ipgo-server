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
    }

    public static void storageCostSchema(JdbcTemplate jdbc) {
        // 기존 harness의 V11 뒤에 portable V19를 적용한다(#111). 테스트 DB는 클래스끼리 공유되므로 한 번만 적용한다.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'product_boxes'", Integer.class) == 0) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migration/V19__create_storage_cost.sql"))
                    .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        }
    }

    public static void clear(JdbcTemplate jdbc) {
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
