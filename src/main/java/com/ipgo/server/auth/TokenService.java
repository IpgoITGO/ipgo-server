package com.ipgo.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenService {
    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final JwtTokens jwt;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(AuthSessionRepository sessions, RefreshTokenRepository refreshTokens, JwtTokens jwt, Clock clock) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.clock = clock;
    }

    @Transactional
    public IssuedTokens login(long userId) {
        var session = sessions.saveAndFlush(new AuthSession(userId, clock.instant().truncatedTo(ChronoUnit.SECONDS)));
        return issue(session);
    }

    // 재사용을 감지한 세션 폐기는 401을 반환해도 commit해야 한다.
    @Transactional(noRollbackFor = AuthTokenException.class)
    public IssuedTokens refresh(String raw) {
        String hash = hashChecked(raw);
        String sessionId = refreshTokens.findSessionId(hash).orElseThrow(TokenService::invalidRefresh);
        AuthSession session = sessions.lockById(sessionId).orElseThrow(TokenService::invalidRefresh);
        if (session.isRevoked()) throw invalidRefresh();
        if (!session.getExpiresAt().isAfter(clock.instant())) {
            throw new AuthTokenException("REFRESH_TOKEN_EXPIRED", "로그인 유지 기간이 만료되었습니다. 다시 로그인해주세요.");
        }
        RefreshToken token = refreshTokens.findById(hash).orElseThrow(TokenService::invalidRefresh);
        if (token.getUsedAt() != null) {
            session.revoke();
            throw new AuthTokenException("REFRESH_TOKEN_REUSED", "사용된 갱신 토큰입니다. 다시 로그인해주세요.");
        }
        token.use(clock.instant());
        return issue(session);
    }

    @Transactional
    public void logout(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) return;
        refreshTokens.findSessionId(hashChecked(raw)).flatMap(sessions::lockById).ifPresent(AuthSession::revoke);
    }

    private IssuedTokens issue(AuthSession session) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.saveAndFlush(new RefreshToken(hashChecked(raw), session.getId()));
        return new IssuedTokens(jwt.issue(session), raw, session.getExpiresAt());
    }

    private static String hashChecked(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw invalidRefresh();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AuthTokenException invalidRefresh() {
        return new AuthTokenException("INVALID_REFRESH_TOKEN", "다시 로그인해주세요.");
    }

    public record IssuedTokens(AccessTokenResponse access, String refreshToken, Instant expiresAt) { }
}
