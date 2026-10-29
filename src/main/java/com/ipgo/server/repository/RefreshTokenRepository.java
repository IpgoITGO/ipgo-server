package com.ipgo.server.repository;

import com.ipgo.server.entity.RefreshToken;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    // 토큰 엔티티는 세션 잠금 이후에 읽어 동시 갱신 시 최신 usedAt을 확인한다.
    @Query("select t.sessionId from RefreshToken t where t.tokenHash = :hash")
    Optional<String> findSessionId(String hash);
}
