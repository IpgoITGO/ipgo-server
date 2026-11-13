package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.infrastructure.BillingSecrets;
import com.ipgo.server.service.AccountWithdrawalService;
import com.ipgo.server.service.EmailVerificationService;
import com.ipgo.server.service.TrialReuseGuard;
import com.ipgo.server.support.TestMailConfiguration;
import com.ipgo.server.support.UploadTestDatabase;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #206 가입 동의(전환 기간) · 무료 체험 재사용 막기
@AutoConfigureMockMvc
@Import(TestMailConfiguration.class)
@TestPropertySource(properties = {"ipgo.mail.from=noreply@example.test", "management.health.mail.enabled=false"})
class SignupTrialReuseIntegrationTests extends DatabaseIntegrationTest {
    static final String PASSWORD = "Test123!";
    static final String VERSION = "2026-11-v1";

    @DynamicPropertySource static void billing(DynamicPropertyRegistry r) {
        byte[] lookup = new byte[32];
        java.util.Arrays.fill(lookup, (byte) 1);
        r.add("ipgo.billing.enabled", () -> "true");
        r.add("ipgo.billing.worker-enabled", () -> "false");
        r.add("ipgo.billing.secret-key", () -> "test_sk_local_fixture_not_a_credential");
        r.add("ipgo.billing.security-key", () -> "local-webhook-fixture-only");
        r.add("ipgo.billing.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("ipgo.billing.lookup-key", () -> Base64.getEncoder().encodeToString(lookup));
        r.add("ipgo.billing.merchant-id", () -> "test-merchant");
        r.add("ipgo.engine.enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired EmailVerificationService verifications;
    @Autowired TestMailConfiguration.RecordingMailSender mail;
    @Autowired AccountWithdrawalService withdrawals;
    @Autowired BillingSecrets secrets;
    @Autowired TrialReuseGuard trialReuse;
    final List<Long> created = new ArrayList<>();
    final List<String> emails = new ArrayList<>();

    @BeforeEach void setup() {
        UploadTestDatabase.withdrawalSchema(jdbc);
        mail.reset();
    }

    @AfterEach void cleanup() {
        for (long id : created) {
            jdbc.update("DELETE FROM billing_subscriptions WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        for (String email : emails) {
            jdbc.update("DELETE FROM email_verifications WHERE email = ?", email);
            jdbc.update("DELETE FROM trial_reuse_fingerprints WHERE email_fingerprint = ?", secrets.fingerprint(email));
        }
    }

    @Test void consentIsCheckedOnlyWhenSentDuringTransition() throws Exception {
        // 지금 가입 화면처럼 동의를 안 보내도 가입돼요(전환 기간). 동의 기록은 비어 있어요.
        long plain = id(signup(email(), Map.of()).andExpect(status().isCreated()));
        assertThat(jdbc.queryForObject("SELECT agreement_version FROM users WHERE id = ?", String.class, plain)).isNull();

        long agreed = id(signup(email(), Map.of("agreements", agreements(true, true, VERSION))).andExpect(status().isCreated()));
        Map<String, Object> row = jdbc.queryForMap("SELECT agreement_version, agreed_at FROM users WHERE id = ?", agreed);
        assertThat(row.get("agreement_version")).isEqualTo(VERSION);
        assertThat(row.get("agreed_at")).isNotNull();

        // 보낸 동의는 검사해요. 틀리면 인증 토큰을 쓰지 않아 고쳐서 다시 보낼 수 있어요.
        String retry = email();
        signup(retry, Map.of("agreements", agreements(true, false, VERSION))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_INPUT")).andExpect(jsonPath("details.field").value("agreements"));
        signup(email(), Map.of("agreements", agreements(true, true, "2025-01-v0"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("agreements"));

        // 전환 기간에는 사업자등록번호 검증 숫자를 보지 않아요(지금 화면 그대로).
        signup(email(), Map.of("businessRegNo", "1234567890")).andExpect(status().isCreated());
    }

    @Test void resignupWithinAYearStartsWithoutTrialAndOthersKeepTheirTrial() throws Exception {
        String email = email();
        long first = id(signup(email, Map.of()).andExpect(status().isCreated()));
        subscription(first).andExpect(jsonPath("status").value("TRIALING"));

        withdrawals.withdraw(first, PASSWORD);
        // 이메일 대신 확인값만 남아요.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trial_reuse_fingerprints WHERE email_fingerprint = ?",
                Integer.class, secrets.fingerprint(email))).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trial_reuse_fingerprints WHERE email_fingerprint LIKE '%@%'",
                Integer.class)).isZero();

        // 같은 이메일(대문자로 써도)로 다시 가입하면 체험 없이 시작해요.
        long again = id(signup(email.toUpperCase(), Map.of()).andExpect(status().isCreated()));
        subscription(again).andExpect(jsonPath("status").value("TRIAL_EXPIRED"))
                .andExpect(jsonPath("entitlements.reason").value("TRIAL_ALREADY_USED"))
                .andExpect(jsonPath("entitlements.canUpload").value(false))
                .andExpect(jsonPath("entitlements.canCalculate").value(false));

        // 처음 가입한 다른 이메일은 지금처럼 14일 체험이에요.
        long fresh = id(signup(email(), Map.of()).andExpect(status().isCreated()));
        subscription(fresh).andExpect(jsonPath("status").value("TRIALING"))
                .andExpect(jsonPath("entitlements.canUpload").value(true));
    }

    @Test void afterOneYearTheRecordIsIgnoredAndPurged() throws Exception {
        String email = email();
        Instant past = Instant.now().minus(400, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO trial_reuse_fingerprints (email_fingerprint, withdrawn_at, expires_at) VALUES (?, ?, ?)",
                secrets.fingerprint(email), Timestamp.from(past), Timestamp.from(past.plus(365, ChronoUnit.DAYS)));

        long id = id(signup(email, Map.of()).andExpect(status().isCreated()));
        subscription(id).andExpect(jsonPath("status").value("TRIALING"));

        trialReuse.purgeExpired();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trial_reuse_fingerprints WHERE email_fingerprint = ?",
                Integer.class, secrets.fingerprint(email))).isZero();
    }

    private String email() {
        String email = "trial-" + UUID.randomUUID() + "@example.test";
        emails.add(email);
        return email;
    }

    private static Map<String, Object> agreements(boolean terms, boolean privacy, String version) {
        return Map.of("terms", terms, "privacy", privacy, "version", version);
    }

    private ResultActions signup(String email, Map<String, Object> extra) throws Exception {
        // 인증 메일은 1분에 한 번이라 같은 테스트 안에서 다시 받을 때는 지난 기록을 지운다.
        jdbc.update("DELETE FROM email_verifications WHERE email = ?", email.toLowerCase());
        verifications.requestCode(email);
        String token = verifications.confirm(email, mail.codes.get(email.toLowerCase()));
        mail.reset();
        Map<String, Object> body = new HashMap<>(Map.of("email", email, "password", PASSWORD, "companyName", "테스트 회사",
                "managerName", "담당자", "verificationToken", token));
        body.putAll(extra);
        return mvc.perform(post("/api/auth/signup").with(realCsrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private long id(ResultActions result) throws Exception {
        long id = mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("userId").longValue();
        created.add(id);
        return id;
    }

    private ResultActions subscription(long userId) throws Exception {
        return mvc.perform(get("/api/billing/subscription").with(jwt().jwt(c -> c.subject(Long.toString(userId)))))
                .andExpect(status().isOk());
    }

    private RequestPostProcessor realCsrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        Cookie cookie = result.getResponse().getCookie("IPGO-XSRF-TOKEN");
        String header = body.get("headerName").stringValue(), token = body.get("token").stringValue();
        return request -> {
            Cookie[] old = request.getCookies() == null ? new Cookie[0] : request.getCookies();
            Cookie[] all = java.util.Arrays.copyOf(old, old.length + 1);
            all[old.length] = cookie;
            request.setCookies(all);
            request.addHeader(header, token);
            return request;
        };
    }
}
