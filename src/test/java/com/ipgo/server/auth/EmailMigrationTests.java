package com.ipgo.server.auth;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailMigrationTests {
    @Test
    void stopsBeforeRemovingEncryptedDataOrLookupHash() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL";
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            Flyway.configure().dataSource(url, "sa", "").target("1").load().migrate();
            statement.executeUpdate("INSERT INTO users (email, email_lookup_hash, password_hash) "
                    + "VALUES ('v1:test-ciphertext', 'test-lookup', 'test-password-hash')");

            assertThatThrownBy(() -> Flyway.configure().dataSource(url, "sa", "").load().migrate())
                    .isInstanceOf(FlywayException.class);

            try (var row = statement.executeQuery("SELECT email, email_lookup_hash, password_hash FROM users")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("v1:test-ciphertext");
                assertThat(row.getString(2)).isEqualTo("test-lookup");
                assertThat(row.getString(3)).isEqualTo("test-password-hash");
            }
        }
    }

    @Test
    void preservesConvertedAccountAndEnforcesEmailSchema() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL";
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            Flyway.configure().dataSource(url, "sa", "").target("1").load().migrate();
            statement.executeUpdate("INSERT INTO users (id, email, email_lookup_hash, password_hash) "
                    + "VALUES (42, 'seller@example.test', 'test-lookup', 'test-password-hash')");

            Flyway.configure().dataSource(url, "sa", "").load().migrate();

            try (var row = statement.executeQuery("SELECT id, email, password_hash, company_name, manager_name, business_reg_no FROM users")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(42);
                assertThat(row.getString(2)).isEqualTo("seller@example.test");
                assertThat(row.getString(3)).isEqualTo("test-password-hash");
                assertThat(row.getString(4)).isNull();
                assertThat(row.getString(5)).isNull();
                assertThat(row.getString(6)).isNull();
                assertThat(row.getMetaData().getColumnTypeName(2)).isEqualTo("CHARACTER VARYING");
                assertThat(row.getMetaData().getPrecision(2)).isEqualTo(254);
            }
            assertThatThrownBy(() -> statement.executeQuery("SELECT email_lookup_hash FROM users"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO users (email, password_hash) "
                    + "VALUES ('seller@example.test', 'another-password-hash')"))
                    .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");
        }
    }
}
