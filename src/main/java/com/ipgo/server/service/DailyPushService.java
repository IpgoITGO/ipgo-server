package com.ipgo.server.service;

import com.ipgo.server.config.PushDeliveryProperties;
import com.ipgo.server.infrastructure.PushSender;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** #190 같은 업무일 예약은 DB에서 하나만 생성한다. 불확실한 전송은 재발송하지 않는다. */
@Service
public class DailyPushService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DashboardService dashboard;
    private final PushSender sender;
    private final Clock clock;
    private final PushDeliveryProperties properties;
    private final ExpiryService expiry;
    private final PlannedReceiptService receipts;
    private long userCursor;

    public DailyPushService(JdbcTemplate jdbc, TransactionTemplate transactions, DashboardService dashboard,
            PushSender sender, Clock clock, PushDeliveryProperties properties, ExpiryService expiry,
            PlannedReceiptService receipts) {
        this.jdbc = jdbc; this.transactions = transactions; this.dashboard = dashboard;
        this.sender = sender; this.clock = clock; this.properties = properties;
        this.expiry = expiry; this.receipts = receipts;
    }

    // #207 하루 한 번 통합 요약. 화면 알림 종과 같은 기준이다.
    // - needed: 대시보드 "지금 넣어야 해요" 상품 수(계산이 최신일 때만)
    // - expired · imminent: 남은 재고가 있는 LOT 중 유통기한이 지났거나 셀러가 정한 임박 기준(기본 30일) 안인 건수
    // - overdue: 도착 예정일이 지났는데 아직 안 들어온 발주(OVERDUE) 건수
    // 알림 설정 · 발송 기록의 kind는 기존 'REPLENISHMENT_NEEDED'를 그대로 쓰고 "하루 요약" 하나를 뜻한다.
    record Summary(int needed, int expired, int imminent, int overdue) {
        int total() { return needed + expired + imminent + overdue; }
        String body() {
            List<String> parts = new java.util.ArrayList<>();
            if (needed > 0) parts.add("입고 필요 " + needed + "개");
            if (expired > 0) parts.add("유통기한 지남 " + expired + "건");
            if (imminent > 0) parts.add("유통기한 임박 " + imminent + "건");
            if (overdue > 0) parts.add("도착 지연 " + overdue + "건");
            return String.join(" · ", parts);
        }
    }

    public void tick() {
        if (!properties.enabled()) return;
        Instant now = clock.instant();
        // claim을 commit한 뒤에만 HTTP를 시작한다. 프로세스 중단/최종 commit 실패도 재전송하지 않는다.
        jdbc.update("UPDATE push_deliveries SET state='UNKNOWN', updated_at=? WHERE state='SENDING' AND updated_at < ?",
                ts(now), ts(now.minus(Duration.ofMinutes(10))));
        List<Long> users = enabledUsers(userCursor);
        if (users.isEmpty() && userCursor != 0) users = enabledUsers(0);
        userCursor = users.isEmpty() ? 0 : users.getLast();
        for (long user : users) prepare(user);
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM push_deliveries WHERE state IN ('PENDING','RETRY') AND next_attempt_at <= ?
                ORDER BY next_attempt_at, id LIMIT 50
                """, Long.class, ts(now));
        for (long id : ids) deliver(id);
    }

    private List<Long> enabledUsers(long after) {
        return jdbc.queryForList("""
                SELECT user_id FROM notification_settings WHERE kind='REPLENISHMENT_NEEDED' AND channel='PUSH'
                AND enabled=TRUE AND user_id > ? ORDER BY user_id LIMIT 200
                """, Long.class, after);
    }

    static Instant scheduled(Instant now, int hour, String zone) {
        ZoneId tz = ZoneId.of(zone);
        return now.atZone(tz).toLocalDate().atTime(hour, 0).atZone(tz).toInstant();
    }

    static boolean due(Instant now, Instant scheduled) {
        return !now.isBefore(scheduled) && now.isBefore(scheduled.plus(Duration.ofHours(1)));
    }

    public void prepare(long userId) {
        transactions.executeWithoutResult(status -> {
            if (!activeUser(userId)) return;
            List<Setting> settings = setting(userId);
            if (settings.isEmpty()) return;
            Setting setting = settings.getFirst();
            Instant now = clock.instant();
            Instant scheduled = scheduled(now, setting.hour(), setting.zone());
            if (!due(now, scheduled)) return;
            LocalDate date = now.atZone(ZoneId.of(setting.zone())).toLocalDate();
            if (!jdbc.queryForList("SELECT id FROM push_daily_summaries WHERE user_id=? AND kind='REPLENISHMENT_NEEDED' AND business_date=?",
                    Long.class, userId, date).isEmpty()) return;
            List<Subscription> subscriptions = subscriptions(userId);
            if (subscriptions.isEmpty() || summary(userId).total() == 0) return;
            jdbc.update("""
                    INSERT INTO push_daily_summaries(user_id,business_date,time_zone,created_at,expires_at)
                    VALUES (?,?,?,?,?)
                    """, userId, date, setting.zone(), ts(now), ts(scheduled.plus(Duration.ofHours(1))));
            long summary = jdbc.queryForObject("SELECT id FROM push_daily_summaries WHERE user_id=? AND business_date=? AND kind='REPLENISHMENT_NEEDED'",
                    Long.class, userId, date);
            for (Subscription subscription : subscriptions) jdbc.update("""
                    INSERT INTO push_deliveries(summary_id,subscription_id,subscription_updated_at,state,next_attempt_at,updated_at)
                    VALUES (?,?,?,'PENDING',?,?)
                    """, summary, subscription.id(), ts(subscription.updated()), ts(now), ts(now));
        });
    }

    Summary summary(long userId) {
        var lots = expiry.list(userId, null, null).summary();
        int overdue = (int) receipts.list(userId).items().stream().filter(r -> "OVERDUE".equals(r.status())).count();
        return new Summary(eligibleCount(userId), (int) lots.expired(), (int) lots.imminent(), overdue);
    }

    // 대시보드와 동일 판정. 새 발주/취소/설정이 아직 계산에 반영되기 전에는 입고 필요 개수를 보수적으로 0으로 본다.
    // (유통기한 · 도착 지연은 계산과 상관없이 센다.)
    int eligibleCount(long userId) {
        var needs = dashboard.needs(userId, "shortage");
        if (needs.runId() == null || !"UP_TO_DATE".equals(needs.resultStatus())
                || needs.daysSinceAsOf() == null || needs.daysSinceAsOf() < 0
                || needs.daysSinceAsOf() > NeedJudgment.MAX_DAYS_SINCE_AS_OF) return 0;
        if (needs.latestUploadId() != null && jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_source_files WHERE run_id=? AND upload_file_id=?",
                Integer.class, needs.runId(), needs.latestUploadId()) == 0) return 0;
        Integer fresh = jdbc.queryForObject("""
                SELECT COUNT(*) FROM calculation_runs r WHERE r.id=? AND r.status='DONE'
                AND r.id=(SELECT MAX(n.id) FROM calculation_runs n WHERE n.warehouse_id=r.warehouse_id)
                AND NOT EXISTS (SELECT 1 FROM planned_receipts p WHERE p.warehouse_id=r.warehouse_id AND p.updated_at>r.created_at)
                AND NOT EXISTS (SELECT 1 FROM replenishment_settings s JOIN products p ON p.id=s.product_id JOIN warehouses w ON w.user_id=p.user_id WHERE w.id=r.warehouse_id AND s.updated_at>r.created_at)
                """, Integer.class, needs.runId());
        return fresh != null && fresh == 1 ? needs.items().size() : 0;
    }

    public void deliver(long id) {
        Instant now = clock.instant();
        Boolean claimed = transactions.execute(status -> jdbc.update("""
                UPDATE push_deliveries SET state='SENDING', attempts=attempts+1, updated_at=?
                WHERE id=? AND state IN ('PENDING','RETRY') AND next_attempt_at<=? AND attempts<3
                """, ts(now), id, ts(now)) == 1);
        if (!Boolean.TRUE.equals(claimed)) return;
        transactions.executeWithoutResult(status -> {
            var rows = jdbc.query("""
                    SELECT s.user_id,s.time_zone,s.business_date,s.created_at,s.expires_at,d.subscription_id,
                        d.subscription_updated_at,d.attempts
                    FROM push_deliveries d JOIN push_daily_summaries s ON s.id=d.summary_id WHERE d.id=?
                    """, (rs, i) -> new Delivery(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getLong(6),
                        rs.getTimestamp(7).toInstant(), rs.getInt(8)), id);
            if (rows.isEmpty()) return;
            Delivery delivery = rows.getFirst();
            // 회원→구독 순서로 잠근다. 탈퇴·설정 변경·구독 이동과 HTTP를 직렬화한다(최대8초 read timeout).
            if (!activeUser(delivery.user())) { finish(id, "CANCELED", null); return; }
            List<Setting> settings = setting(delivery.user());
            nowCheck(id, delivery, settings);
        });
    }

    private void nowCheck(long id, Delivery delivery, List<Setting> settings) {
        Instant now = clock.instant();
        if (!now.isBefore(delivery.expires())) { finish(id, "EXPIRED", null); return; }
        if (settings.isEmpty() || !settings.getFirst().zone().equals(delivery.zone())
                || settings.getFirst().updated().isAfter(delivery.created())
                || !due(now, scheduled(now, settings.getFirst().hour(), settings.getFirst().zone()))
                || !now.atZone(ZoneId.of(delivery.zone())).toLocalDate().equals(delivery.date())) {
            finish(id, "CANCELED", null); return;
        }
        var subscriptions = jdbc.query("""
                SELECT id,updated_at,endpoint,p256dh,auth_secret FROM push_subscriptions
                WHERE id=? AND user_id=? AND active=TRUE FOR UPDATE
                """, (rs,i) -> new Subscription(rs.getLong(1),rs.getTimestamp(2).toInstant(),rs.getString(3),rs.getString(4),rs.getString(5)),
                delivery.subscription(), delivery.user());
        if (subscriptions.isEmpty() || !subscriptions.getFirst().updated().equals(delivery.subscriptionUpdated())) {
            finish(id, "CANCELED", null); return;
        }
        // 다른 worker는 claim에서 제외한다. 오래된 claim 회수와의 경합도 확인한다.
        String state = jdbc.queryForObject("SELECT state FROM push_deliveries WHERE id=? FOR UPDATE", String.class,id);
        if (!"SENDING".equals(state)) return;
        Summary summary = summary(delivery.user());
        if (summary.total() == 0) { finish(id,"CANCELED",null); return; }
        Subscription subscription = subscriptions.getFirst();
        // 개수와 이동 경로만 담는다(상품명 · 금액 없음).
        byte[] payload = ("{\"notificationId\":\"daily-" + id + "\",\"title\":\"확인이 필요한 재고가 있어요\","
                + "\"body\":\"" + summary.body() + "\",\"url\":\"/dashboard\"}").getBytes(StandardCharsets.UTF_8);
        PushSender.Result response = sender.send(subscription.endpoint(), subscription.p256dh(), subscription.auth(), payload);
        int code = response.status();
        if (code == 404 || code == 410) {
            jdbc.update("UPDATE push_subscriptions SET active=FALSE,updated_at=? WHERE id=?",ts(clock.instant()),subscription.id());
            finish(id,"EXPIRED",code);
        } else if ((code == 429 || code == 503) && delivery.attempts() < 3) {
            // 명시적 거절만 재시도하며 5/15분 기다린다.
            Duration backoff = Duration.ofMinutes(delivery.attempts() == 1 ? 5 : 15);
            if (response.retryAfter().compareTo(backoff) > 0) backoff = response.retryAfter();
            Instant retry = clock.instant().plus(backoff);
            if (retry.isBefore(delivery.expires())) jdbc.update(
                    "UPDATE push_deliveries SET state='RETRY',provider_status=?,next_attempt_at=?,updated_at=? WHERE id=?",
                    code,ts(retry),ts(clock.instant()),id);
            else finish(id,"EXPIRED",code);
        } else finish(id,code >= 200 && code < 300 ? "SENT" : code < 0 ? "UNKNOWN" : "FAILED",code < 0 ? null : code);
    }

    private boolean activeUser(long userId) {
        return !jdbc.queryForList("SELECT id FROM users WHERE id=? AND withdrawn_at IS NULL FOR UPDATE",Long.class,userId).isEmpty();
    }
    private List<Setting> setting(long userId) {
        return jdbc.query("SELECT send_hour,time_zone,updated_at FROM notification_settings WHERE user_id=? AND kind='REPLENISHMENT_NEEDED' AND channel='PUSH' AND enabled=TRUE",
                (rs,i) -> new Setting(rs.getInt(1),rs.getString(2),rs.getTimestamp(3).toInstant()),userId);
    }
    private List<Subscription> subscriptions(long userId) {
        return jdbc.query("SELECT id,updated_at,endpoint,p256dh,auth_secret FROM push_subscriptions WHERE user_id=? AND active=TRUE",
                (rs,i)->new Subscription(rs.getLong(1),rs.getTimestamp(2).toInstant(),rs.getString(3),rs.getString(4),rs.getString(5)),userId);
    }
    private void finish(long id,String state,Integer code) {
        jdbc.update("UPDATE push_deliveries SET state=?,provider_status=?,updated_at=? WHERE id=?",state,code,ts(clock.instant()),id);
    }
    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
    private record Setting(int hour,String zone,Instant updated) { }
    private record Subscription(long id,Instant updated,String endpoint,String p256dh,String auth) {
        @Override public String toString() { return "Subscription[id="+id+"]"; }
    }
    private record Delivery(long user,String zone,LocalDate date,Instant created,Instant expires,
            long subscription,Instant subscriptionUpdated,int attempts) { }
}
