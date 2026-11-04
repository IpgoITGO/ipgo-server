package com.ipgo.server.integration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseSchemaV11Tests {
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void migrate() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL";
        connection = DriverManager.getConnection(url, "sa", "");
        statement = connection.createStatement();
        Flyway.configure()
                .dataSource(new SingleConnectionDataSource(connection, true))
                .target("11")
                .load()
                .migrate();

        statement.executeUpdate("INSERT INTO users (id, email, password_hash) VALUES (1, 'a@example.test', 'hash')");
        statement.executeUpdate("INSERT INTO warehouses (id, user_id, name) VALUES (10, 1, '기본 창고')");
        statement.executeUpdate("INSERT INTO products (id, user_id, sku_barcode, sku_name) "
                + "VALUES (100, 1, 'SKU-100', '상품')");
        statement.executeUpdate("INSERT INTO inventory_units "
                + "(id, product_id, warehouse_id, source_inventory_key, received_on) "
                + "VALUES (1000, 100, 10, 'LOT-100', DATE '2026-10-01')");
        statement.executeUpdate("INSERT INTO upload_files "
                + "(id, warehouse_id, original_name, storage_key, file_hash, revision_no, period_start, period_end, status) "
                + "VALUES (2000, 10, '2026-10.xlsx', '1/upload', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', "
                + "1, DATE '2026-10-01', DATE '2026-10-31', 'DONE')");
        statement.executeUpdate("INSERT INTO calculation_runs "
                + "(id, warehouse_id, run_type, as_of_date, status, engine_version, selection_snapshot, created_at) "
                + "VALUES (3000, 10, 'CALCULATE', DATE '2026-10-31', 'DONE', '0.1.0', JSON '{}', CURRENT_TIMESTAMP)");
        statement.executeUpdate("INSERT INTO calculation_results "
                + "(id, run_id, product_id, origin_date, start_date, multiplier, horizon_days, status, input_snapshot) "
                + "VALUES (4000, 3000, 100, DATE '2026-10-31', DATE '2026-11-01', 1.000, 30, 'OK', JSON '{}')");
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
    }

    @Test
    void storesDecimalDailyStocksAndRejectsDuplicateSources() throws Exception {
        statement.executeUpdate("INSERT INTO daily_stock_records "
                + "(upload_file_id, inventory_unit_id, stock_date, opening_qty, inbound_qty, outbound_qty, "
                + "adjustment_qty, closing_qty, source_sheet, source_row) "
                + "VALUES (2000, 1000, DATE '2026-10-01', 10.125, 2.500, 1.250, -0.125, 11.250, 'Sheet1', 2)");
        statement.executeUpdate("INSERT INTO product_daily_stocks "
                + "(product_id, upload_file_id, stock_date, opening_qty, inbound_qty, outbound_qty, adjustment_qty, "
                + "closing_qty, is_valid_observation) "
                + "VALUES (100, 2000, DATE '2026-10-01', 10.125, 2.500, 1.250, -0.125, 11.250, TRUE)");

        try (var row = statement.executeQuery("SELECT closing_qty FROM product_daily_stocks")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("11.250"));
        }
        assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO product_daily_stocks "
                + "(product_id, upload_file_id, stock_date, opening_qty, inbound_qty, outbound_qty, adjustment_qty, "
                + "closing_qty, is_valid_observation) "
                + "VALUES (100, 2000, DATE '2026-10-01', 0, 0, 0, 0, 0, FALSE)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void keepsCalculationSourcesAndOneResultPerScenarioType() throws Exception {
        statement.executeUpdate("INSERT INTO run_source_files (run_id, upload_file_id) VALUES (3000, 2000)");
        statement.executeUpdate("INSERT INTO scenario_results "
                + "(result_id, scenario_type, scenario_qty, expected_ending_stock, unmet_demand_qty, daily_projection) "
                + "VALUES (4000, 'P100', 12, 3.500, 0.000, JSON '[]')");
        statement.executeUpdate("INSERT INTO backtest_metrics "
                + "(result_id, actual_outbound_qty, absolute_error, cover_success, ending_stock_qty, excess_flag, "
                + "shortage_days, average_stock_qty) VALUES (4000, 10.000, 1.000, TRUE, 2.000, FALSE, 0, 3.000)");
        statement.executeUpdate("INSERT INTO ai_explanations "
                + "(result_id, mode, status, request_payload, validation_passed, versions) "
                + "VALUES (4000, 'TEMPLATE', 'DONE', JSON '{}', TRUE, JSON '{}')");

        assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO scenario_results "
                + "(result_id, scenario_type, scenario_qty, expected_ending_stock, unmet_demand_qty, daily_projection) "
                + "VALUES (4000, 'P100', 13, 1.000, 0.000, JSON '[]')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO ai_explanations "
                + "(result_id, mode, status, request_payload, validation_passed, versions) "
                + "VALUES (4000, 'LLM', 'DONE', JSON '{}', TRUE, JSON '{}')"))
                .isInstanceOf(SQLException.class);
    }
}
