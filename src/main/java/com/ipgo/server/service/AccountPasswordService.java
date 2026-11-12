package com.ipgo.server.service;

import com.ipgo.server.exception.AccountException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// #192 로그인한 상태에서 비밀번호 바꾸기. 로그인 전에 쓰는 비밀번호 찾기(#173)와 달리 지금 비밀번호로 본인을 확인한다.
@Service
public class AccountPasswordService {
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public AccountPasswordService(PasswordEncoder passwordEncoder, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    // 회원 row를 잠가 탈퇴(#172)와 섞이지 않게 한다. 바꾸면 지금 쓰는 세션만 남기고 다른 기기의 세션은 모두 폐기한다.
    public void change(long userId, String currentSessionId, String currentPassword, String newPassword) {
        transactions.executeWithoutResult(status -> {
            List<String> hashes = jdbc.queryForList(
                    "SELECT password_hash FROM users WHERE id = ? AND withdrawn_at IS NULL FOR UPDATE", String.class, userId);
            if (hashes.isEmpty()) throw new UsernameNotFoundException("이메일 또는 비밀번호를 확인해주세요.");
            if (!passwordEncoder.matches(currentPassword, hashes.getFirst())) {
                throw AccountException.wrongPassword("currentPassword");
            }
            jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(newPassword), userId);
            jdbc.update("UPDATE auth_sessions SET revoked = TRUE WHERE user_id = ? AND id <> ?", userId, currentSessionId);
        });
    }
}
