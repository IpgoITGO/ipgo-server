package com.ipgo.server.entity;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {
    @Id @Column(name = "token_hash", length = 64) private String tokenHash;
    @Column(name = "session_id", nullable = false, length = 36) private String sessionId;
    @Column(name = "used_at") private Instant usedAt;

    public RefreshToken(String tokenHash, String sessionId) {
        this.tokenHash = tokenHash;
        this.sessionId = sessionId;
    }

    public void use(Instant now) { usedAt = now; }
}
