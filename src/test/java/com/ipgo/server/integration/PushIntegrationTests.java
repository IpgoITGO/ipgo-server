package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.config.PushProperties;
import com.ipgo.server.exception.PushException;
import com.ipgo.server.service.AccountWithdrawalService;
import com.ipgo.server.service.PushSubscriptionService;
import com.ipgo.server.support.UploadTestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #190 Web Push 1단계: 브라우저 알림 등록 · 해제와 알림 설정
@AutoConfigureMockMvc
@TestPropertySource(properties = "ipgo.push.public-key=BPushTestPublicKey_only-for-tests")
class PushIntegrationTests extends DatabaseIntegrationTest {
    static final String CHROME = "https://fcm.googleapis.com/fcm/send/abc:def";
    static final String PASSWORD = "Test123!";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired AccountWithdrawalService withdrawals;
    final List<Long> users = new ArrayList<>();
    long owner, other;

    @BeforeEach void setup() {
        UploadTestDatabase.withdrawalSchema(jdbc);
        owner = user();
        other = user();
    }

    @AfterEach void cleanup() {
        for (long id : users) {
            jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ?", id);
            jdbc.update("DELETE FROM notification_settings WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test void publicKeyIsConfiguredValueOr503WhenMissing() throws Exception {
        send(owner, HttpMethod.GET, "/api/push/public-key", null).andExpect(status().isOk())
                .andExpect(jsonPath("publicKey").value("BPushTestPublicKey_only-for-tests"));
        var off = new PushSubscriptionService(new PushProperties("", List.of("fcm.googleapis.com")), jdbc, null, null);
        assertThatThrownBy(off::publicKey).isInstanceOf(PushException.class)
                .satisfies(e -> assertThat(((PushException) e).code()).isEqualTo("PUSH_UNAVAILABLE"));
    }

    @Test void sameBrowserIsSavedOnceAndMovesToTheAccountThatRegisteredLast() throws Exception {
        long id = mapper.readTree(subscribe(owner, CHROME, "p256-first", "auth-first").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("subscriptionId").longValue();

        // 같은 브라우저를 다시 등록하면 새로 만들지 않고 키만 바꿔요. 응답에 주소 · 키는 없어요.
        subscribe(owner, CHROME, "p256-second", "auth-second").andExpect(status().isOk())
                .andExpect(jsonPath("subscriptionId").value(id))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fcm"))));
        assertThat(jdbc.queryForObject("SELECT p256dh FROM push_subscriptions WHERE id = ?", String.class, id))
                .isEqualTo("p256-second");

        // 같은 브라우저에서 다른 계정으로 등록하면 그 계정으로 옮겨요.
        subscribe(other, CHROME, "p256-other", "auth-other").andExpect(status().isOk())
                .andExpect(jsonPath("subscriptionId").value(id));
        assertThat(jdbc.queryForObject("SELECT user_id FROM push_subscriptions WHERE id = ?", Long.class, id)).isEqualTo(other);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions", Integer.class)).isEqualTo(1);

        // 다른 브라우저(Firefox)는 따로 저장해요.
        subscribe(other, "https://updates.push.services.mozilla.com/wpush/v2/xyz", "p256", "auth")
                .andExpect(status().isCreated());
    }

    @Test void onlyHttpsEndpointsOfKnownPushServicesAreAccepted() throws Exception {
        for (String endpoint : List.of("http://fcm.googleapis.com/fcm/send/a", "https://169.254.169.254/latest",
                "https://localhost/push", "https://evil.example.com/fcm", "https://fcm.googleapis.com.evil.com/a",
                "https://user@fcm.googleapis.com/a", "https://fcm.googleapis.com:8443/a", "not a url")) {
            subscribe(owner, endpoint, "p256", "auth").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("code").value("INVALID_INPUT")).andExpect(jsonPath("details.field").value("endpoint"));
        }
        subscribe(owner, CHROME, "not base64!", "auth").andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("keys.p256dh"));
        send(owner, HttpMethod.POST, "/api/push/subscriptions", "{\"endpoint\": \"" + CHROME + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("keys"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions", Integer.class)).isZero();
    }

    @Test void onlyTheOwnerCanDeleteASubscription() throws Exception {
        long id = mapper.readTree(subscribe(owner, CHROME, "p256", "auth").andReturn().getResponse().getContentAsString())
                .get("subscriptionId").longValue();

        send(other, HttpMethod.DELETE, "/api/push/subscriptions/" + id, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("code").value("NOT_FOUND"));
        send(owner, HttpMethod.DELETE, "/api/push/subscriptions/" + id, null).andExpect(status().isNoContent());
        send(owner, HttpMethod.DELETE, "/api/push/subscriptions/" + id, null).andExpect(status().isNotFound());
    }

    // 찬님 요청: 로그아웃할 때 id를 몰라도 브라우저 주소로 지운다. 없어도 204, 남의 등록은 안 지워진다.
    @Test void logoutCanDeleteThisBrowserByEndpoint() throws Exception {
        subscribe(owner, CHROME, "p256", "auth").andExpect(status().isCreated());
        String firefox = "https://updates.push.services.mozilla.com/wpush/v2/other";
        subscribe(other, firefox, "p256", "auth").andExpect(status().isCreated());

        unsubscribe(owner, firefox).andExpect(status().isNoContent());   // 남의 브라우저
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?", Integer.class, other)).isOne();

        unsubscribe(owner, CHROME).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?", Integer.class, owner)).isZero();
        unsubscribe(owner, CHROME).andExpect(status().isNoContent());   // 이미 지웠어도 성공
        send(owner, HttpMethod.DELETE, "/api/push/subscriptions", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("endpoint"));
    }

    private ResultActions unsubscribe(long userId, String endpoint) throws Exception {
        return send(userId, HttpMethod.DELETE, "/api/push/subscriptions", mapper.writeValueAsString(Map.of("endpoint", endpoint)));
    }

    @Test void settingsStartOffAndChangeOnlyTheSentFields() throws Exception {
        settings(owner).andExpect(jsonPath("enabled").value(false)).andExpect(jsonPath("sendHour").value(9))
                .andExpect(jsonPath("timeZone").value("Asia/Seoul"));

        patch(owner, "{\"enabled\": true}").andExpect(status().isOk()).andExpect(jsonPath("enabled").value(true))
                .andExpect(jsonPath("sendHour").value(9));
        patch(owner, "{\"sendHour\": 21, \"timeZone\": \"America/New_York\"}").andExpect(status().isOk())
                .andExpect(jsonPath("enabled").value(true)).andExpect(jsonPath("sendHour").value(21))
                .andExpect(jsonPath("timeZone").value("America/New_York"));
        settings(owner).andExpect(jsonPath("enabled").value(true)).andExpect(jsonPath("sendHour").value(21));
        // 다른 회원 설정은 그대로예요.
        settings(other).andExpect(jsonPath("enabled").value(false));

        patch(owner, "{\"sendHour\": 24}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("sendHour"));
        for (String zone : List.of("+09:00", "Mars/Base", "")) {
            patch(owner, "{\"timeZone\": \"" + zone + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("details.field").value("timeZone"));
        }
        settings(owner).andExpect(jsonPath("sendHour").value(21)).andExpect(jsonPath("timeZone").value("America/New_York"));
    }

    @Test void withdrawalRemovesSubscriptionsAndSettings() throws Exception {
        subscribe(owner, CHROME, "p256", "auth").andExpect(status().isCreated());
        patch(owner, "{\"enabled\": true}").andExpect(status().isOk());

        withdrawals.withdraw(owner, PASSWORD);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_settings WHERE user_id = ?", Integer.class, owner)).isZero();
    }

    private long user() {
        String email = "push-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, ?)", email, encoder.encode(PASSWORD));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    private ResultActions subscribe(long userId, String endpoint, String p256dh, String auth) throws Exception {
        return send(userId, HttpMethod.POST, "/api/push/subscriptions", mapper.writeValueAsString(
                Map.of("endpoint", endpoint, "keys", Map.of("p256dh", p256dh, "auth", auth))));
    }

    private ResultActions settings(long userId) throws Exception {
        return send(userId, HttpMethod.GET, "/api/notifications/settings", null).andExpect(status().isOk());
    }

    private ResultActions patch(long userId, String body) throws Exception {
        return send(userId, HttpMethod.PATCH, "/api/notifications/settings", body);
    }

    private ResultActions send(long userId, HttpMethod method, String path, String body) throws Exception {
        var builder = request(method, path).with(jwt().jwt(claims -> claims.subject(Long.toString(userId))));
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(builder);
    }
}
