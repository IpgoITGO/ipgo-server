package com.ipgo.server.product;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSchemaTests {
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void migrate() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL";
        connection = DriverManager.getConnection(url, "sa", "");
        statement = connection.createStatement();
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        statement.executeUpdate("INSERT INTO users (id, email, password_hash) VALUES (1, 'a@example.test', 'hash')");
        statement.executeUpdate("INSERT INTO users (id, email, password_hash) VALUES (2, 'b@example.test', 'hash')");
        statement.executeUpdate("INSERT INTO warehouses (id, user_id, name) VALUES (10, 1, '기본 창고')");
        statement.executeUpdate("INSERT INTO products (id, user_id, sku_barcode, sku_name) VALUES (100, 1, '8800272420016', '케어트릿 120g')");
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
    }

    @Test
    void newProductIsActiveByDefault() throws Exception {
        try (var row = statement.executeQuery("SELECT is_active, created_at FROM products WHERE id = 100")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getBoolean(1)).isTrue();
            assertThat(row.getTimestamp(2)).isNotNull();
        }
    }

    @Test
    void sameBarcodeIsOneProductPerUser() throws Exception {
        assertThatThrownBy(() -> statement.executeUpdate(
                "INSERT INTO products (user_id, sku_barcode, sku_name) VALUES (1, '8800272420016', '다른 이름')"))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");

        statement.executeUpdate("INSERT INTO products (user_id, sku_barcode, sku_name) VALUES (2, '8800272420016', '케어트릿 120g')");
    }

    @Test
    void inventoryUnitIsSplitByReceivedDate() throws Exception {
        statement.executeUpdate("INSERT INTO inventory_units (product_id, warehouse_id, source_inventory_key, received_on) "
                + "VALUES (100, 10, 'MIX-001', DATE '2026-09-01')");
        statement.executeUpdate("INSERT INTO inventory_units (product_id, warehouse_id, source_inventory_key, received_on) "
                + "VALUES (100, 10, 'MIX-001', DATE '2026-09-15')");

        assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inventory_units "
                + "(product_id, warehouse_id, source_inventory_key, received_on) VALUES (100, 10, 'MIX-001', DATE '2026-09-01')"))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");

        try (var row = statement.executeQuery("SELECT COUNT(*) FROM inventory_units WHERE product_id = 100")) {
            row.next();
            assertThat(row.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void replenishmentSettingIsOnePerProductAndPositive() throws Exception {
        statement.executeUpdate("INSERT INTO replenishment_settings (product_id, lead_time_days, updated_at) "
                + "VALUES (100, 7, CURRENT_TIMESTAMP)");

        assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO replenishment_settings (product_id, updated_at) "
                + "VALUES (100, CURRENT_TIMESTAMP)"))
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");
        for (String column : new String[] {"lead_time_days", "review_cycle_days", "order_unit_qty"}) {
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE replenishment_settings SET " + column + " = 0"))
                    .isInstanceOf(SQLException.class);
        }
        assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO replenishment_settings (product_id, updated_at) "
                + "VALUES (999, CURRENT_TIMESTAMP)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void rejectsNegativeCostAndUnknownOwner() {
        assertThatThrownBy(() -> statement.executeUpdate("UPDATE products SET unit_cost = -1 WHERE id = 100"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> statement.executeUpdate(
                "INSERT INTO products (user_id, sku_barcode, sku_name) VALUES (999, '123', '주인 없음')"))
                .isInstanceOf(SQLException.class);
    }
}
