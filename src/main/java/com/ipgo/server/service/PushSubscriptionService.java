package com.ipgo.server.service;

import com.ipgo.server.config.PushProperties;
import com.ipgo.server.dto.request.PushSubscriptionRequest;
import com.ipgo.server.dto.response.PushPublicKey;
import com.ipgo.server.exception.PushException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// #190 브라우저 알림 등록. 브라우저(기기)마다 1행이고, 같은 브라우저는 endpoint hash로 찾아 새로 만들지 않는다.
// - 다른 계정이 쓰던 브라우저로 다시 등록하면 지금 계정으로 옮긴다(계정 전환).
// - endpoint · 키는 응답과 로그에 내보내지 않는다.
@Service
@EnableConfigurationProperties(PushProperties.class)
public class PushSubscriptionService {
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+=*");

    private final PushProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PushSubscriptionService(PushProperties properties, JdbcTemplate jdbc, TransactionTemplate transactions,
            Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record Saved(long subscriptionId, boolean created) { }

    public PushPublicKey publicKey() {
        if (properties.publicKey().isBlank()) throw PushException.unavailable();
        return new PushPublicKey(properties.publicKey());
    }

    public Saved save(long userId, PushSubscriptionRequest request) {
        publicKey();
        checkEndpoint(request.endpoint());
        if (!BASE64URL.matcher(request.keys().p256dh()).matches()) throw PushException.invalid("keys.p256dh");
        if (!BASE64URL.matcher(request.keys().auth()).matches()) throw PushException.invalid("keys.auth");
        String hash = sha256(request.endpoint());
        try {
            return upsert(userId, hash, request);
        } catch (DuplicateKeyException sameBrowserAtOnce) {
            // 같은 브라우저가 동시에 두 번 등록하면 늦은 쪽은 먼저 만든 행을 고친다.
            return upsert(userId, hash, request);
        }
    }

    private Saved upsert(long userId, String hash, PushSubscriptionRequest request) {
        return transactions.execute(status -> {
            Timestamp now = Timestamp.from(clock.instant());
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM push_subscriptions WHERE endpoint_hash = ? FOR UPDATE", Long.class, hash);
            if (!ids.isEmpty()) {
                jdbc.update("""
                        UPDATE push_subscriptions SET user_id = ?, endpoint = ?, p256dh = ?, auth_secret = ?, active = TRUE,
                            updated_at = ? WHERE id = ?
                        """, userId, request.endpoint(), request.keys().p256dh(), request.keys().auth(), now, ids.getFirst());
                return new Saved(ids.getFirst(), false);
            }
            jdbc.update("""
                    INSERT INTO push_subscriptions (user_id, endpoint_hash, endpoint, p256dh, auth_secret, active,
                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, TRUE, ?, ?)
                    """, userId, hash, request.endpoint(), request.keys().p256dh(), request.keys().auth(), now, now);
            return new Saved(jdbc.queryForObject("SELECT id FROM push_subscriptions WHERE endpoint_hash = ?",
                    Long.class, hash), true);
        });
    }

    // 로그아웃 직전에 부른다. 내 등록이 아니거나 없으면 404(다른 회원 등록이 있는지 알리지 않는다).
    public void delete(long userId, long subscriptionId) {
        if (jdbc.update("DELETE FROM push_subscriptions WHERE id = ? AND user_id = ?", subscriptionId, userId) == 0) {
            throw PushException.notFound();
        }
    }

    // 로그아웃할 때 브라우저 주소로 지운다. 내 등록이 없거나 이미 지웠어도 성공으로 보고(로그아웃이 실패하지 않게),
    // 다른 회원의 등록은 지우지 않는다.
    public void deleteByEndpoint(long userId, String endpoint) {
        jdbc.update("DELETE FROM push_subscriptions WHERE endpoint_hash = ? AND user_id = ?", sha256(endpoint), userId);
    }

    // 서버가 이 주소로 직접 요청을 보내므로 https와 알려진 브라우저 푸시 서버만 받는다(SSRF 방지).
    private void checkEndpoint(String endpoint) {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException exception) {
            throw PushException.invalid("endpoint");
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = host != null && properties.allowedHosts().stream()
                .map(rule -> rule.toLowerCase(Locale.ROOT))
                .anyMatch(rule -> rule.startsWith(".") ? host.endsWith(rule) : host.equals(rule));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || !allowed) {
            throw PushException.invalid("endpoint");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
