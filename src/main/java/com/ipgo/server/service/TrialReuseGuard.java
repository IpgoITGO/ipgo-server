package com.ipgo.server.service;

import com.ipgo.server.config.BillingProperties;
import com.ipgo.server.infrastructure.BillingSecrets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// #206 무료 체험 재사용 막기.
// 탈퇴할 때 이메일 대신 이메일 확인값(결제 조회용 키로 만든 HMAC)만 1년간 남기고,
// 1년 안에 같은 이메일로 다시 가입한 회원은 체험 없이 시작한다. 결제 기능이 꺼진 환경은 체험 제한도 없으므로 남기지 않는다.
@Component
@EnableScheduling
public class TrialReuseGuard {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final BillingProperties properties;
    private final BillingSecrets secrets;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TrialReuseGuard(BillingProperties properties, BillingSecrets secrets, JdbcTemplate jdbc, Clock clock) {
        this.properties = properties;
        this.secrets = secrets;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private boolean active() {
        return properties.enabled() && !properties.lookupKey().isBlank();
    }

    // 탈퇴 transaction 안에서, 이메일을 지우기 전에 부른다. 같은 이메일로 또 탈퇴하면 그 시점부터 다시 1년이다.
    public void rememberWithdrawal(String email) {
        if (!active() || email == null) return;
        Instant now = clock.instant();
        Instant expires = now.atZone(SEOUL).plusYears(1).toInstant();
        String fingerprint = secrets.fingerprint(email);
        int updated = jdbc.update("UPDATE trial_reuse_fingerprints SET withdrawn_at = ?, expires_at = ? WHERE email_fingerprint = ?",
                Timestamp.from(now), Timestamp.from(expires), fingerprint);
        if (updated == 0) {
            jdbc.update("INSERT INTO trial_reuse_fingerprints (email_fingerprint, withdrawn_at, expires_at) VALUES (?, ?, ?)",
                    fingerprint, Timestamp.from(now), Timestamp.from(expires));
        }
    }

    // 가입 transaction 안에서 부른다. 이때의 판정을 계정에 남겨, 나중에 확인값이 만료 · 삭제돼도 바뀌지 않게 한다.
    public void decideAtSignup(long userId, String email) {
        if (usedBefore(email)) jdbc.update("UPDATE users SET trial_reused = TRUE WHERE id = ?", userId);
    }

    // 지울 시각이 지난 기록은 하루 정리 전이라도 없는 것으로 본다.
    boolean usedBefore(String email) {
        if (!active() || email == null) return false;
        return !jdbc.queryForList("SELECT email_fingerprint FROM trial_reuse_fingerprints WHERE email_fingerprint = ? AND expires_at > ?",
                String.class, secrets.fingerprint(email), Timestamp.from(clock.instant())).isEmpty();
    }

    // 매일 새벽 3시 30분(한국 시간)에 1년이 지난 확인값을 지운다.
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void purgeExpired() {
        jdbc.update("DELETE FROM trial_reuse_fingerprints WHERE expires_at <= ?", Timestamp.from(clock.instant()));
    }
}
