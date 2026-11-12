package com.ipgo.server.service;

import com.ipgo.server.dto.request.NotificationSettingsRequest;
import com.ipgo.server.dto.response.NotificationSettings;
import com.ipgo.server.exception.PushException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// #190 입고 필요 알림 설정. 표는 "알림 종류 × 받는 방법"마다 1행이고, 지금 API는 입고 필요 × Web Push 한 줄만 쓴다.
// 행이 없으면 기본값(꺼짐 · 9시 · 한국 시간)이다.
@Service
public class NotificationSettingsService {
    static final String KIND = "REPLENISHMENT_NEEDED";
    static final String CHANNEL = "PUSH";
    static final NotificationSettings DEFAULTS = new NotificationSettings(false, 9, "Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public NotificationSettingsService(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    public NotificationSettings get(long userId) {
        List<NotificationSettings> rows = jdbc.query("""
                SELECT enabled, send_hour, time_zone FROM notification_settings
                WHERE user_id = ? AND kind = ? AND channel = ?
                """, (rs, i) -> new NotificationSettings(rs.getBoolean(1), rs.getInt(2), rs.getString(3)),
                userId, KIND, CHANNEL);
        return rows.isEmpty() ? DEFAULTS : rows.getFirst();
    }

    // 보낸 칸만 바꾼다. 같은 회원의 동시 요청이 서로 덮어쓰지 않게 회원 row를 잠근다.
    public NotificationSettings update(long userId, NotificationSettingsRequest request) {
        // 시간대는 Asia/Seoul 같은 지역 이름만 받는다(고정 오프셋은 서머타임을 따라가지 못한다).
        if (request.timeZone() != null && !ZoneId.getAvailableZoneIds().contains(request.timeZone())) {
            throw PushException.invalid("timeZone");
        }
        return transactions.execute(status -> {
            jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
            NotificationSettings current = get(userId);
            NotificationSettings next = new NotificationSettings(
                    request.enabled() != null ? request.enabled() : current.enabled(),
                    request.sendHour() != null ? request.sendHour() : current.sendHour(),
                    request.timeZone() != null ? request.timeZone() : current.timeZone());
            Timestamp now = Timestamp.from(clock.instant());
            int updated = jdbc.update("""
                    UPDATE notification_settings SET enabled = ?, send_hour = ?, time_zone = ?, updated_at = ?
                    WHERE user_id = ? AND kind = ? AND channel = ?
                    """, next.enabled(), next.sendHour(), next.timeZone(), now, userId, KIND, CHANNEL);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO notification_settings (user_id, kind, channel, enabled, send_hour, time_zone, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, userId, KIND, CHANNEL, next.enabled(), next.sendHour(), next.timeZone(), now);
            }
            return next;
        });
    }
}
