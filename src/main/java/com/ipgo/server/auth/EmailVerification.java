package com.ipgo.server.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "email_verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// shortcut: 만료된 행은 재요청 때 재사용한다. 공개 운영 전 보관 기간과 정리 작업을 추가한다.
public class EmailVerification {
    @Id @Column(length = 254) private String email;
    @Column(name = "code_hash", length = 255) private String codeHash;
    @Column(name = "code_expires_at") private Instant codeExpiresAt;
    @Column(name = "last_sent_at") private Instant lastSentAt;
    @Column(name = "failed_attempts", nullable = false) private int failedAttempts;
    @Column(name = "token_hash", length = 64) private String tokenHash;
    @Column(name = "token_expires_at") private Instant tokenExpiresAt;

    void issueCode(String hash, Instant now) {
        codeHash = hash;
        codeExpiresAt = now.plusSeconds(600);
        lastSentAt = now;
        failedAttempts = 0;
        consumeToken();
    }

    void failCode() {
        failedAttempts++;
        if (failedAttempts >= 5) invalidateCode();
    }

    void invalidateCode() {
        codeHash = null;
        codeExpiresAt = null;
    }

    void issueToken(String hash, Instant now) {
        invalidateCode();
        tokenHash = hash;
        tokenExpiresAt = now.plusSeconds(1800);
    }

    void consumeToken() {
        tokenHash = null;
        tokenExpiresAt = null;
    }
}
