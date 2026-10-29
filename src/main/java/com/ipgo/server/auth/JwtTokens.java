package com.ipgo.server.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public class JwtTokens implements JwtDecoder {
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final AuthSessionRepository sessions;
    private final Clock clock;

    public JwtTokens(@Value("${ipgo.auth.jwt-secret:}") String secret,
            AuthSessionRepository sessions, Clock clock) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("IPGO_JWT_SECRET에는 32바이트 이상의 무작위 키를 Base64로 설정해야 합니다.");
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("IPGO_JWT_SECRET에는 32바이트 이상의 무작위 키를 Base64로 설정해야 합니다.");
        }
        var key = new SecretKeySpec(bytes, "HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.sessions = sessions;
        this.clock = clock;
        decoder.setJwtValidator(this::validate);
    }

    AccessTokenResponse issue(AuthSession session) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = now.plusSeconds(900);
        if (expires.isAfter(session.getExpiresAt())) expires = session.getExpiresAt();
        var claims = JwtClaimsSet.builder().issuer("ipgo-server").audience(List.of("ipgo-api"))
                .subject(session.getUserId().toString()).id(UUID.randomUUID().toString())
                .claim("sid", session.getId()).issuedAt(now).expiresAt(expires).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new AccessTokenResponse(token, "Bearer", expires.getEpochSecond() - now.getEpochSecond());
    }

    @Override
    public Jwt decode(String token) throws JwtException { return decoder.decode(token); }

    private OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant now = clock.instant();
        Long userId;
        String sessionId;
        try {
            userId = Long.valueOf(jwt.getSubject());
            sessionId = jwt.getClaimAsString("sid");
            if (userId <= 0 || !UUID.fromString(sessionId).toString().equals(sessionId)
                    || !"ipgo-server".equals(jwt.getClaimAsString("iss"))
                    || !jwt.getAudience().contains("ipgo-api")
                    || jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(now)
                    || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now))) {
                return invalid("INVALID_ACCESS_TOKEN");
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            return invalid("INVALID_ACCESS_TOKEN");
        }
        if (!jwt.getExpiresAt().isAfter(now)) return invalid("ACCESS_TOKEN_EXPIRED");
        if (!sessions.existsByIdAndUserIdAndRevokedFalseAndExpiresAtAfter(sessionId, userId, now)) {
            return invalid("SESSION_REVOKED");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult invalid(String code) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, "인증 토큰을 확인해주세요.", null));
    }
}
