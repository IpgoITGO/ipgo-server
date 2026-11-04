package com.ipgo.server.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "auth_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthSession {
    @Id @Column(length = 36) private String id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(nullable = false) private boolean revoked;

    public AuthSession(long userId, Instant now) {
        id = UUID.randomUUID().toString();
        this.userId = userId;
        createdAt = now;
        expiresAt = now.plusSeconds(7 * 24 * 60 * 60);
    }

    public void revoke() { revoked = true; }
}
