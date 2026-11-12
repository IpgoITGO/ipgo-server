package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.request.NotificationSettingsRequest;
import com.ipgo.server.dto.request.PushSubscriptionRequest;
import com.ipgo.server.exception.PushException;
import com.ipgo.server.service.AccountWithdrawalService;
import com.ipgo.server.service.NotificationSettingsService;
import com.ipgo.server.service.PushSubscriptionService;
import com.ipgo.server.support.UploadTestDatabase;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "ipgo.push.public-key=BPushTestPublicKey_only-for-tests")
class PushWithdrawalConcurrencyTests extends DatabaseIntegrationTest {
    private static final String PASSWORD = "Test123!";
    private static final PushSubscriptionRequest SUBSCRIPTION = new PushSubscriptionRequest(
            "https://fcm.googleapis.com/fcm/send/withdrawal-race",
            new PushSubscriptionRequest.Keys("p256", "auth"));
    private static final NotificationSettingsRequest SETTINGS = new NotificationSettingsRequest(true, 9, "Asia/Seoul");

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired AccountWithdrawalService withdrawals;
    @Autowired PushSubscriptionService subscriptions;
    @Autowired NotificationSettingsService settings;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    private long owner;
    private long other;

    enum Write { SUBSCRIPTION, SETTINGS }

    @BeforeEach void setup() {
        UploadTestDatabase.withdrawalSchema(jdbc);
        owner = user();
        other = user();
    }

    @AfterEach void cleanup() {
        for (long user : new long[] {owner, other}) {
            jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM notification_settings WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    // jwt()는 필터 인증을 이미 통과한 요청이 탈퇴 commit 뒤 컨트롤러에 도달하는 순서를 재현한다.
    @ParameterizedTest
    @EnumSource(Write.class)
    void authenticatedRequestArrivingAfterWithdrawalIsRejected(Write write) throws Exception {
        withdrawals.withdraw(owner, PASSWORD);

        var request = write == Write.SUBSCRIPTION
                ? post("/api/push/subscriptions").content("""
                        {"endpoint":"https://fcm.googleapis.com/fcm/send/withdrawal-race",
                         "keys":{"p256dh":"p256","auth":"auth"}}
                        """)
                : patch("/api/notifications/settings").content("{\"enabled\":true}");
        mvc.perform(request.with(jwt().jwt(claims -> claims.subject(Long.toString(owner))))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("ACCOUNT_WITHDRAWN"));
        assertRemoved();
    }

    @Test void withdrawnAccountCannotTakeAnotherAccountsExistingBrowser() {
        long id = subscriptions.save(other, SUBSCRIPTION).subscriptionId();
        withdrawals.withdraw(owner, PASSWORD);

        assertThatThrownBy(() -> subscriptions.save(owner, SUBSCRIPTION)).isInstanceOfSatisfying(PushException.class,
                exception -> assertThat(exception.code()).isEqualTo("ACCOUNT_WITHDRAWN"));
        assertThat(jdbc.queryForObject("SELECT user_id FROM push_subscriptions WHERE id = ?", Long.class, id))
                .isEqualTo(other);
        assertRemoved();
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void writeWaitingForWithdrawalCommitCannotRestoreData(Write write) throws Exception {
        // 행이 이미 있는 갱신 경로에서도 탈퇴가 먼저 commit하면 삭제 상태를 유지한다.
        subscriptions.save(owner, SUBSCRIPTION);
        settings.update(owner, SETTINGS);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> pending = transactions.execute(status -> {
                withdrawals.withdraw(owner, PASSWORD);
                CountDownLatch started = new CountDownLatch(1);
                Future<?> future = executor.submit(() -> {
                    started.countDown();
                    write(write);
                });
                assertBlocked(started, future);
                return future; // 이 transaction이 commit된 뒤 대기하던 쓰기가 재개된다.
            });

            assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(PushException.class)
                    .satisfies(exception -> assertThat(((PushException) exception.getCause()).code())
                            .isEqualTo("ACCOUNT_WITHDRAWN"));
            assertRemoved();
        }
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void withdrawalWaitingForWriteCommitRemovesTheCommittedData(Write write) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> pending = transactions.execute(status -> {
                write(write);
                CountDownLatch started = new CountDownLatch(1);
                Future<?> future = executor.submit(() -> {
                    started.countDown();
                    withdrawals.withdraw(owner, PASSWORD);
                });
                assertBlocked(started, future);
                return future;
            });

            pending.get(10, TimeUnit.SECONDS);
            assertRemoved();
            assertThat(jdbc.queryForObject("SELECT withdrawn_at IS NOT NULL FROM users WHERE id = ?",
                    Boolean.class, owner)).isTrue();
        }
    }

    private static void assertBlocked(CountDownLatch started, Future<?> pending) {
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        assertThatThrownBy(() -> pending.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    }

    private void write(Write write) {
        if (write == Write.SUBSCRIPTION) subscriptions.save(owner, SUBSCRIPTION);
        else settings.update(owner, SETTINGS);
    }

    private void assertRemoved() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?", Integer.class, owner))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_settings WHERE user_id = ?", Integer.class, owner))
                .isZero();
    }

    private long user() {
        String email = "push-race-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, ?)", email, encoder.encode(PASSWORD));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
