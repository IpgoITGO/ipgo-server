package com.ipgo.server.upload;

import org.springframework.jdbc.core.JdbcTemplate;

// 테스트 DB는 테스트 클래스끼리 공유된다. 업로드가 만든 행을 지워야 다른 테스트의 회원 삭제가 막히지 않는다.
final class UploadTestDatabase {
    private UploadTestDatabase() { }

    static long newUser(JdbcTemplate jdbc, String email) {
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    static void clear(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM upload_files");
        jdbc.update("DELETE FROM replenishment_settings");
        jdbc.update("DELETE FROM inventory_units");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }
}
