package com.ipgo.server.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

// H2/V11 harness에도 운영과 같은 portable V29를 JPA validate 전에 적용한다.
@TestConfiguration(proxyBeanMethods = false)
public class ProductActivityTestSchema {
    @Bean
    FlywayMigrationStrategy productActivityMigration() {
        return flyway -> {
            flyway.migrate();
            var source = flyway.getConfiguration().getDataSource();
            var jdbc = new JdbcTemplate(source);
            if (jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE LOWER(table_name) = 'products' AND LOWER(column_name) = 'activation_mode'
                    """, Integer.class) == 0) {
                new ResourceDatabasePopulator(new ClassPathResource(
                        "db/migration/V29__preserve_product_activation_mode.sql")).execute(source);
            }
        };
    }
}
