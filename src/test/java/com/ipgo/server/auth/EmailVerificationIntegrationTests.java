package com.ipgo.server.auth;

import com.ipgo.server.DatabaseIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Import(TestMailConfiguration.class)
@TestPropertySource(properties = {"ipgo.mail.from=noreply@example.test", "management.health.mail.enabled=false"})
class EmailVerificationIntegrationTests extends DatabaseIntegrationTest {
    private static final String EMAIL = "seller@example.test";
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired EmailVerificationService service;
    @Autowired EmailVerificationRepository verifications;
    @Autowired UserAccountRepository users;
    @Autowired AuthService auth;
    @Autowired TestMailConfiguration.RecordingMailSender mail;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;

    @BeforeEach
    void reset() {
        users.deleteAll();
        verifications.deleteAll();
        mail.reset();
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void completeAnonymousVerificationAndSignupContract() throws Exception {
        mvc.perform(get("/api/auth/email-availability").param("email", " SELLER@example.test "))
                .andExpect(status().isOk()).andExpect(content().json("{\"available\":true}"));
        send(" SELLER@example.test ").andExpect(status().isAccepted())
                .andExpect(content().json("{\"expiresIn\":600}"));
        String code = mail.codes.get(EMAIL);
        assertThat(code).matches("[0-9]{6}");
        assertThat(verifications.findById(EMAIL).orElseThrow().getCodeHash()).isNotEqualTo(code);
        String token = mapper.readTree(confirm(EMAIL, code).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("verificationToken").stringValue();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(verifications.findById(EMAIL).orElseThrow().getTokenHash()).hasSize(64).isNotEqualTo(token);
        jsonPost("/api/auth/signup", signupBody(token)).andExpect(status().isCreated())
                .andExpect(jsonPath("userId").isNumber()).andExpect(jsonPath("id").doesNotExist())
                .andExpect(jsonPath("companyName").value("테스트 회사"))
                .andExpect(jsonPath("managerName").value("담당자"))
                .andExpect(jsonPath("verificationToken").doesNotExist());
        assertThat(users.findByEmail(EMAIL).orElseThrow().getBusinessRegNo()).isEqualTo("0123456789");
        assertThat(verifications.findById(EMAIL).orElseThrow().getTokenHash()).isNull();
        mvc.perform(get("/api/auth/email-availability").param("email", "SELLER@example.test"))
                .andExpect(status().isOk()).andExpect(jsonPath("available").value(false));
        send(EMAIL).andExpect(status().isConflict()).andExpect(jsonPath("code").value("DUPLICATE_EMAIL"));
        jsonPost("/api/auth/signup", signupBody(token)).andExpect(status().isConflict());
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void limitsResendAndReplacesPreviousCodeAndToken() throws Exception {
        service.requestCode(EMAIL);
        String oldCode = mail.codes.get(EMAIL);
        when(clock.instant()).thenReturn(NOW.plusSeconds(59));
        send(EMAIL).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("code").value("EMAIL_VERIFICATION_RATE_LIMITED"));
        assertThat(mail.sent).hasValue(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        send(EMAIL).andExpect(status().isAccepted());
        confirm(EMAIL, oldCode).andExpect(status().isBadRequest());
        String token = service.confirm(EMAIL, mail.codes.get(EMAIL));
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        send(EMAIL).andExpect(status().isAccepted());
        jsonPost("/api/auth/signup", signupBody(token)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_VERIFICATION_TOKEN"));
    }

    @Test
    void fifthWrongCodePermanentlyInvalidatesItUntilResent() throws Exception {
        service.requestCode(EMAIL);
        String correct = mail.codes.get(EMAIL);
        for (int attempt = 1; attempt <= 5; attempt++) {
            confirm(EMAIL, wrongCode(correct)).andExpect(status().isBadRequest());
            assertThat(verifications.findById(EMAIL).orElseThrow().getFailedAttempts()).isEqualTo(attempt);
        }
        confirm(EMAIL, correct).andExpect(status().isBadRequest());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        service.requestCode(EMAIL);
        assertThat(verifications.findById(EMAIL).orElseThrow().getFailedAttempts()).isZero();
        confirm(EMAIL, mail.codes.get(EMAIL)).andExpect(status().isOk());
    }

    @Test
    void correctCodeAfterFourFailuresWorksOnlyOnce() throws Exception {
        service.requestCode(EMAIL);
        String correct = mail.codes.get(EMAIL);
        for (int attempt = 0; attempt < 4; attempt++) confirm(EMAIL, wrongCode(correct)).andExpect(status().isBadRequest());
        confirm(EMAIL, correct).andExpect(status().isOk());
        confirm(EMAIL, correct).andExpect(status().isBadRequest());
    }

    @Test
    void expiresCodeAtExactlyTenMinutes() throws Exception {
        service.requestCode(EMAIL);
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        confirm(EMAIL, mail.codes.get(EMAIL)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("EMAIL_CODE_EXPIRED"));
        assertThat(verifications.findById(EMAIL).orElseThrow().getCodeHash()).isNull();
    }

    @Test
    void tokenExpiresThirtyMinutesAfterConfirmationAndCannotChangeEmail() throws Exception {
        service.requestCode(EMAIL);
        when(clock.instant()).thenReturn(NOW.plusSeconds(599));
        String token = service.confirm(EMAIL, mail.codes.get(EMAIL));
        var otherEmail = signupBody(token);
        otherEmail.put("email", "other@example.test");
        jsonPost("/api/auth/signup", otherEmail).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_VERIFICATION_TOKEN"));
        when(clock.instant()).thenReturn(NOW.plusSeconds(599 + 1800));
        jsonPost("/api/auth/signup", signupBody(token)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_VERIFICATION_TOKEN"));
        assertThat(users.count()).isZero();
    }

    @Test
    void signupCanRetryAfterRollbackAndConsumesTokenOnce() {
        String token = verifiedToken();
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.consumeToken(EMAIL, token);
            throw new IllegalStateException("test-only rollback");
        })).isInstanceOf(IllegalStateException.class);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1799));
        auth.register(signupRequest(token));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.consumeToken(EMAIL, token)))
                .isInstanceOf(EmailVerificationException.class);
    }

    @Test
    void missingForgedAndMalformedTokensCannotRegister() throws Exception {
        String validToken = verifiedToken();
        var body = signupBody("x".repeat(43));
        jsonPost("/api/auth/signup", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_VERIFICATION_TOKEN"));
        body.remove("verificationToken");
        jsonPost("/api/auth/signup", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("verificationToken"));
        body.put("verificationToken", "invalid");
        jsonPost("/api/auth/signup", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("verificationToken"));
        assertThat(users.count()).isZero();
        jsonPost("/api/auth/signup", signupBody(validToken)).andExpect(status().isCreated());
    }

    @Test
    void fieldValidationReturnsNamesWithoutRejectedValues() throws Exception {
        for (var entry : Map.of("companyName", " ", "managerName", "x".repeat(51),
                "businessRegNo", "123-45-6789", "password", "short-pass").entrySet()) {
            var body = signupBody("x".repeat(43));
            body.put(entry.getKey(), entry.getValue());
            String response = jsonPost("/api/auth/signup", body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("details.field").value(entry.getKey()))
                    .andReturn().getResponse().getContentAsString();
            if (!entry.getValue().isBlank()) assertThat(response).doesNotContain(entry.getValue());
        }
        mvc.perform(get("/api/auth/email-availability")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("email"));
        mvc.perform(get("/api/auth/email-availability").param("email", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("email"));
        send("invalid").andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("email"));
        confirm(EMAIL, "12345").andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("code"));
    }

    @Test
    void acceptsBoundaryLengthsAndBlankOptionalBusinessNumber() throws Exception {
        var body = signupBody(verifiedToken());
        body.put("companyName", "가".repeat(50));
        body.put("managerName", "김");
        body.put("businessRegNo", "");
        jsonPost("/api/auth/signup", body).andExpect(status().isCreated());
        assertThat(users.findByEmail(EMAIL).orElseThrow().getBusinessRegNo()).isNull();
    }

    @Test
    void mailFailureDoesNotConsumeCooldownOrDestroyExistingToken() throws Exception {
        mail.fail = true;
        send(EMAIL).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("code").value("EMAIL_DELIVERY_UNAVAILABLE"));
        assertThat(verifications.findById(EMAIL)).isEmpty();
        mail.fail = false;
        String token = verifiedToken();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        mail.fail = true;
        send(EMAIL).andExpect(status().isServiceUnavailable());
        jsonPost("/api/auth/signup", signupBody(token)).andExpect(status().isCreated());
    }

    @Test
    void newPostEndpointsRequireCsrf() throws Exception {
        for (String path : new String[] {"/api/auth/email-verifications", "/api/auth/email-verifications/confirm"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(mail.sent).hasValue(0);
    }

    @Test
    void concurrentResendsProduceOneMailAndConcurrentSignupCreatesOneUser() throws Exception {
        var sends = concurrent(() -> {
            try { service.requestCode(EMAIL); return "sent"; }
            catch (EmailVerificationException exception) { return exception.code(); }
        });
        assertThat(sends).containsExactlyInAnyOrder("sent", "EMAIL_VERIFICATION_RATE_LIMITED");
        assertThat(mail.sent).hasValue(1);
        String token = service.confirm(EMAIL, mail.codes.get(EMAIL));
        var signups = concurrent(() -> {
            try { auth.register(signupRequest(token)); return "created"; }
            catch (EmailVerificationException exception) { return "rejected"; }
        });
        assertThat(signups).containsExactlyInAnyOrder("created", "rejected");
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void simultaneousConfirmationsIssueOnlyOneToken() throws Exception {
        service.requestCode(EMAIL);
        String code = mail.codes.get(EMAIL);
        assertThat(concurrent(() -> {
            try { service.confirm(EMAIL, code); return "verified"; }
            catch (EmailVerificationException exception) { return exception.code(); }
        })).containsExactlyInAnyOrder("verified", "INVALID_EMAIL_CODE");
    }

    private java.util.List<String> concurrent(Callable<String> operation) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> gated = () -> { ready.countDown(); start.await(5, TimeUnit.SECONDS); return operation.call(); };
            var first = executor.submit(gated);
            var second = executor.submit(gated);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }

    private String verifiedToken() {
        service.requestCode(EMAIL);
        return service.confirm(EMAIL, mail.codes.get(EMAIL));
    }

    private SignupRequest signupRequest(String token) {
        return new SignupRequest(EMAIL, "test-only-password-123!", "테스트 회사", "담당자", null, token);
    }

    private Map<String, Object> signupBody(String token) {
        return new HashMap<>(Map.of("email", EMAIL, "password", "test-only-password-123!",
                "companyName", " 테스트 회사 ", "managerName", " 담당자 ", "businessRegNo", "0123456789",
                "verificationToken", token));
    }

    private String wrongCode(String correct) { return correct.equals("000000") ? "000001" : "000000"; }
    private ResultActions send(String email) throws Exception {
        return jsonPost("/api/auth/email-verifications", Map.of("email", email));
    }
    private ResultActions confirm(String email, String code) throws Exception {
        return jsonPost("/api/auth/email-verifications/confirm", Map.of("email", email, "code", code));
    }
    private ResultActions jsonPost(String path, Object body) throws Exception {
        return mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
}
