package com.ipgo.server.service;

import com.ipgo.server.config.PushDeliveryProperties;
import com.ipgo.server.dto.response.ReplenishmentNeeds;
import com.ipgo.server.infrastructure.PushSender;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyPushServiceTests {
    JdbcTemplate jdbc;
    com.zaxxer.hikari.HikariDataSource source;
    String postgresUrl;
    String schema;
    TransactionTemplate tx;
    DashboardService dashboard;
    PushSender sender;
    ExpiryService expiry;
    PlannedReceiptService receipts;
    MutableClock clock;
    DailyPushService service;
    ReplenishmentNeeds needs;

    @BeforeEach void setup() throws Exception {
        source = new com.zaxxer.hikari.HikariDataSource();
        postgresUrl = System.getenv("IPGO_PUSH_TEST_DB_URL");
        if (postgresUrl != null && !postgresUrl.isBlank()) {
            schema = "push_test_"+UUID.randomUUID().toString().replace("-", "");
            try (var connection=java.sql.DriverManager.getConnection(postgresUrl,"postgres", ""); var statement=connection.createStatement()) {
                statement.execute("CREATE SCHEMA "+schema);
            }
            source.setJdbcUrl(postgresUrl);
            source.setConnectionInitSql("SET search_path TO "+schema);
        } else source.setJdbcUrl("jdbc:h2:mem:push-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        source.setUsername(schema == null ? "sa" : "postgres"); source.setPassword(""); source.setMaximumPoolSize(6);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,withdrawn_at TIMESTAMP WITH TIME ZONE)");
        for (String name : List.of("V26__create_push_notifications.sql","V28__create_push_delivery_history.sql")) {
            var resource = new org.springframework.core.io.ClassPathResource("db/migration/"+name);
            String sql = resource.getContentAsString(StandardCharsets.UTF_8).split("-- PostgreSQL")[0];
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
        }
        jdbc.execute("CREATE TABLE calculation_runs(id BIGINT PRIMARY KEY,warehouse_id BIGINT,status VARCHAR(20),created_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE run_source_files(run_id BIGINT,upload_file_id BIGINT)");
        jdbc.update("INSERT INTO run_source_files VALUES(1,1)");
        jdbc.execute("CREATE TABLE planned_receipts(warehouse_id BIGINT,updated_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE products(id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE warehouses(id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE replenishment_settings(product_id BIGINT,updated_at TIMESTAMP WITH TIME ZONE)");
        clock = new MutableClock(Instant.parse("2026-11-12T00:05:00Z"));
        jdbc.update("INSERT INTO users VALUES(1,NULL),(2,NULL)");
        jdbc.update("INSERT INTO warehouses VALUES(1,1)");
        jdbc.update("INSERT INTO calculation_runs VALUES(1,1,'DONE',?)",ts(clock.instant().minusSeconds(3600)));
        jdbc.update("INSERT INTO notification_settings VALUES(1,'REPLENISHMENT_NEEDED','PUSH',TRUE,9,'Asia/Seoul',?)",ts(clock.instant().minusSeconds(10)));
        subscription("a");
        dashboard = mock(DashboardService.class);
        needs = mock(ReplenishmentNeeds.class);
        when(needs.runId()).thenReturn(1L);
        when(needs.latestUploadId()).thenReturn(1L);
        when(needs.resultStatus()).thenReturn("UP_TO_DATE");
        when(needs.daysSinceAsOf()).thenReturn(1L);
        when(needs.items()).thenReturn(List.of(mock(ReplenishmentNeeds.Item.class)));
        when(dashboard.needs(1L,"shortage")).thenReturn(needs);
        sender = mock(PushSender.class);
        when(sender.send(anyString(),anyString(),anyString(),any())).thenReturn(new PushSender.Result(201));
        // #207 유통기한 · 도착 지연은 기본 0건
        expiry = mock(ExpiryService.class);
        receipts = mock(PlannedReceiptService.class);
        lots(0, 0);
        overdue(0);
        service = service(true);
    }
    DailyPushService service(boolean enabled) {
        return new DailyPushService(jdbc,tx,dashboard,sender,clock,
                new PushDeliveryProperties(enabled,enabled?"test":"","https://example.test"),expiry,receipts);
    }
    void lots(int expired, int imminent) {
        when(expiry.list(1L,null,null)).thenReturn(new com.ipgo.server.dto.response.ExpiryList(null,30,
                new com.ipgo.server.dto.response.ExpiryList.Summary(expired,imminent,0,0),List.of()));
    }
    void overdue(int count) {
        var items = new java.util.ArrayList<com.ipgo.server.dto.response.PlannedReceiptItem>();
        for (long i = 0; i < count; i++) items.add(new com.ipgo.server.dto.response.PlannedReceiptItem(i,1L,"상품",1L,
                LocalDate.of(2026,11,1),null,"OVERDUE",true));
        items.add(new com.ipgo.server.dto.response.PlannedReceiptItem(99L,1L,"상품",1L,LocalDate.of(2026,12,1),null,"PENDING",true));
        when(receipts.list(1L)).thenReturn(new com.ipgo.server.dto.response.PlannedReceiptItem.Page(items,items.size()));
    }
    String sentBody() {
        var payload = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(sender,atLeastOnce()).send(anyString(),anyString(),anyString(),payload.capture());
        return new String(payload.getValue(),StandardCharsets.UTF_8);
    }
    @org.junit.jupiter.api.AfterEach void close() throws Exception {
        source.close();
        if (schema != null) try (var connection=java.sql.DriverManager.getConnection(postgresUrl,"postgres", ""); var statement=connection.createStatement()) {
            statement.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }
    void subscription(String suffix) {
        jdbc.update("INSERT INTO push_subscriptions(user_id,endpoint_hash,endpoint,p256dh,auth_secret,created_at,updated_at) VALUES(1,?,?, 'key','auth',?,?)",
                suffix.repeat(64),"https://fcm.googleapis.com/"+suffix,ts(clock.instant().minusSeconds(5)),ts(clock.instant().minusSeconds(5)));
    }
    String state() { return jdbc.queryForObject("SELECT state FROM push_deliveries ORDER BY id LIMIT 1",String.class); }

    @Test void restartAndMultiplePollsSendOnceWithMinimalPayload() {
        service.tick(); service.tick();
        var restarted = service(true);
        restarted.tick();
        var payload = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(sender,times(1)).send(anyString(),anyString(),anyString(),payload.capture());
        String body = new String(payload.getValue(),StandardCharsets.UTF_8);
        assertThat(body).contains("daily-1","입고 필요 1개","/dashboard").doesNotContain("유통기한","도착 지연","skuName","amount","email");
        assertThat(state()).isEqualTo("SENT");
    }
    @Test void concurrentServersClaimOnlyOneSummaryAndOneDelivery() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a=pool.submit(()->{ await(gate); service.prepare(1); });
            var b=pool.submit(()->{ await(gate); service.prepare(1); });
            gate.countDown(); a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_daily_summaries",Integer.class)).isOne();
            var c=pool.submit(()->service.deliver(1));var d=pool.submit(()->service.deliver(1));
            c.get(10,TimeUnit.SECONDS);d.get(10,TimeUnit.SECONDS);
        }
        verify(sender,times(1)).send(anyString(),anyString(),anyString(),any());
    }
    @Test void partialFailureRetriesOnlyRefusedDeviceAndStopsAtThree() {
        subscription("b");
        when(sender.send(endsWith("/b"),anyString(),anyString(),any())).thenReturn(new PushSender.Result(503));
        service.tick(); clock.advance(Duration.ofMinutes(5));service.tick();
        clock.advance(Duration.ofMinutes(15));service.tick();service.tick();
        verify(sender,times(1)).send(endsWith("/a"),anyString(),anyString(),any());
        verify(sender,times(3)).send(endsWith("/b"),anyString(),anyString(),any());
        assertThat(jdbc.queryForObject("SELECT state FROM push_deliveries ORDER BY id DESC LIMIT 1",String.class)).isEqualTo("FAILED");
    }
    @Test void providerRetryAfterBeyondWindowExpiresWithoutAnotherRequest() {
        when(sender.send(anyString(),anyString(),anyString(),any())).thenReturn(new PushSender.Result(429,Duration.ofHours(1)));
        service.tick();clock.advance(Duration.ofMinutes(10));service.tick();
        assertThat(state()).isEqualTo("EXPIRED");
        verify(sender,times(1)).send(anyString(),anyString(),anyString(),any());
    }
    @Test void reschedulingWithinSameBusinessDateDoesNotDuplicateSentSummary() {
        service.tick();jdbc.update("UPDATE notification_settings SET send_hour=10,updated_at=?",ts(clock.instant().plusSeconds(1)));
        clock.advance(Duration.ofHours(1));service.tick();
        verify(sender,times(1)).send(anyString(),anyString(),anyString(),any());
    }

    @Test void ambiguousNetworkResultIsNeverAutomaticallyRetried() {
        when(sender.send(anyString(),anyString(),anyString(),any())).thenReturn(new PushSender.Result(-1));
        service.tick();clock.advance(Duration.ofMinutes(20));service.tick();
        assertThat(state()).isEqualTo("UNKNOWN");
        verify(sender,times(1)).send(anyString(),anyString(),anyString(),any());
    }
    @Test void expiredProviderSubscriptionIsDeactivated() {
        when(sender.send(anyString(),anyString(),anyString(),any())).thenReturn(new PushSender.Result(410));
        service.tick();
        assertThat(state()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT active FROM push_subscriptions",Boolean.class)).isFalse();
    }
    @Test void optOutBetweenQueueAndSendCancels() {
        service.prepare(1);jdbc.update("UPDATE notification_settings SET enabled=FALSE");service.deliver(1);
        assertThat(state()).isEqualTo("CANCELED");verifyNoInteractions(sender);
    }
    @Test void withdrawalBetweenQueueAndSendCancels() {
        service.prepare(1);jdbc.update("UPDATE users SET withdrawn_at=? WHERE id=1",ts(clock.instant()));service.deliver(1);
        assertThat(state()).isEqualTo("CANCELED");verifyNoInteractions(sender);
    }
    @Test void transferredOrRefreshedSubscriptionIsNotSentToOldOwner() {
        service.prepare(1);jdbc.update("UPDATE push_subscriptions SET user_id=2");service.deliver(1);
        assertThat(state()).isEqualTo("CANCELED");verifyNoInteractions(sender);
    }
    @Test void changedSubscriptionKeysCancelTheSnapshot() {
        service.prepare(1);jdbc.update("UPDATE push_subscriptions SET updated_at=?",ts(clock.instant()));service.deliver(1);
        assertThat(state()).isEqualTo("CANCELED");verifyNoInteractions(sender);
    }
    @Test void failedMissingOrOldCalculationAndZeroTargetsAreSkipped() {
        when(needs.resultStatus()).thenReturn("STALE");service.prepare(1);
        when(needs.resultStatus()).thenReturn("UP_TO_DATE");when(needs.runId()).thenReturn(null);service.prepare(1);
        when(needs.runId()).thenReturn(1L);when(needs.daysSinceAsOf()).thenReturn(8L);service.prepare(1);
        when(needs.daysSinceAsOf()).thenReturn(1L);when(needs.items()).thenReturn(List.of());service.prepare(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_daily_summaries",Integer.class)).isZero();verifyNoInteractions(sender);
    }
    // #207 입고 필요가 0개여도 유통기한 · 도착 지연이 있으면 한 알림으로 보내고, 0건 항목은 문장에서 뺀다.
    @Test void expiryAndOverdueAloneStillSendOneCombinedSummary() {
        when(needs.items()).thenReturn(List.of());
        lots(3, 2);
        overdue(1);
        service.tick(); service.tick();
        verify(sender,times(1)).send(anyString(),anyString(),anyString(),any());
        assertThat(sentBody()).contains("유통기한 지남 3건 · 유통기한 임박 2건 · 도착 지연 1건","/dashboard")
                .doesNotContain("입고 필요","skuName","amount");
    }
    // #207 계산이 오래돼 입고 필요는 세지 않아도, 유통기한은 계산과 상관없이 알린다.
    @Test void staleCalculationDropsOnlyTheReplenishmentPart() {
        when(needs.resultStatus()).thenReturn("STALE");
        lots(1, 0);
        service.tick();
        assertThat(sentBody()).contains("유통기한 지남 1건").doesNotContain("입고 필요","임박","도착 지연");
    }
    // #207 미해결 항목은 다음 날에도 다시 요약한다(처음 생겼을 때만 보내지 않는다).
    @Test void unresolvedItemsAreSummarizedAgainTheNextDay() {
        when(needs.items()).thenReturn(List.of());
        lots(1, 0);
        service.tick(); clock.advance(Duration.ofDays(1)); service.tick();
        verify(sender,times(2)).send(anyString(),anyString(),anyString(),any());
    }
    @Test void uploadNotIncludedInLatestCalculationIsNotUsedForNotification() {
        when(needs.latestUploadId()).thenReturn(2L);service.prepare(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_daily_summaries",Integer.class)).isZero();
        jdbc.update("INSERT INTO run_source_files VALUES(1,2)");service.prepare(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_daily_summaries",Integer.class)).isOne();
    }

    @Test void newerQueuedCalculationAndUnreflectedReceiptChangesAreSkipped() {
        jdbc.update("INSERT INTO calculation_runs VALUES(2,1,'QUEUED',?)",ts(clock.instant()));service.prepare(1);
        jdbc.update("DELETE FROM calculation_runs WHERE id=2");
        jdbc.update("INSERT INTO planned_receipts VALUES(1,?)",ts(clock.instant()));service.prepare(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_daily_summaries",Integer.class)).isZero();
    }
    @Test void elapsedWindowExpiresQueuedDeliveryAndStaleClaimBecomesUnknown() {
        service.prepare(1);clock.advance(Duration.ofHours(1));service.deliver(1);
        assertThat(state()).isEqualTo("EXPIRED");verifyNoInteractions(sender);
        jdbc.update("UPDATE push_deliveries SET state='SENDING',updated_at=?",ts(clock.instant().minusSeconds(601)));
        service.tick();assertThat(state()).isEqualTo("UNKNOWN");verifyNoInteractions(sender);
    }
    @Test void nextBusinessDayCanSendAgainButDisabledWorkerDoesNothing() {
        service.tick();clock.advance(Duration.ofDays(1));service.tick();
        verify(sender,times(2)).send(anyString(),anyString(),anyString(),any());
        reset(sender);
        service(false).tick();
        verifyNoInteractions(sender);
    }
    @Test void timezoneDayBoundaryGraceAndDstAreExplicit() {
        Instant midnight = Instant.parse("2026-11-11T15:00:00Z");
        assertThat(DailyPushService.scheduled(midnight,0,"Asia/Seoul")).isEqualTo(midnight);
        assertThat(DailyPushService.due(midnight.minusNanos(1),midnight)).isFalse();
        assertThat(DailyPushService.due(midnight.plusSeconds(3599),midnight)).isTrue();
        assertThat(DailyPushService.due(midnight.plusSeconds(3600),midnight)).isFalse();
        // DST gap은 다음 유효 시각, overlap은 첫 시각. 날짜 unique로 두 번째 반복 시각을 막는다.
        assertThat(DailyPushService.scheduled(Instant.parse("2026-03-08T07:01:00Z"),2,"America/New_York"))
                .isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
        assertThat(DailyPushService.scheduled(Instant.parse("2026-11-01T06:01:00Z"),1,"America/New_York"))
                .isEqualTo(Instant.parse("2026-11-01T05:00:00Z"));
    }
    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
    private static void await(CountDownLatch gate) { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); } }
    static final class MutableClock extends Clock {
        Instant now; MutableClock(Instant now) { this.now=now; }
        void advance(Duration duration) { now=now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); }
        @Override public Instant instant() { return now; }
    }
}
