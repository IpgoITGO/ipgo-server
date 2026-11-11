package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.support.TestMailConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #173 아이디 찾기 · 비밀번호 재설정. 로그인 전에 쓰고, 가입 여부를 알리지 않는다.
@AutoConfigureMockMvc
@Import(TestMailConfiguration.class)
@TestPropertySource(properties = {"ipgo.mail.from=noreply@example.test", "management.health.mail.enabled=false"})
class AccountRecoveryIntegrationTests extends DatabaseIntegrationTest {
    static final String OLD_PASSWORD = "Test123!";
    static final String NEW_PASSWORD = "Newpass9#";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired TestMailConfiguration.RecordingMailSender mail;
    final List<String> emails = new ArrayList<>();

    @BeforeEach void setup() { mail.reset(); }

    @AfterEach void cleanup() {
        for (String email : emails) {
            jdbc.update("DELETE FROM users WHERE email = ?", email);
            jdbc.update("DELETE FROM email_verifications WHERE email = ?", email);
        }
    }

    @Test void findIdMasksTheLatestAccountForTheBusinessNumber() throws Exception {
        String number = businessNumber();
        user("older" + UUID.randomUUID() + "@example.test", number);
        String latest = user("se" + UUID.randomUUID() + "@a.test", number);
        // 탈퇴(#172)로 이메일이 바뀐 계정은 찾지 않아요.
        user("withdrawn-" + UUID.randomUUID() + "@withdrawn.invalid", number);

        String dashed = number.substring(0, 3) + "-" + number.substring(3, 5) + "-" + number.substring(5);
        findId(dashed).andExpect(status().isOk()).andExpect(jsonPath("maskedEmail").value("se***@a.test"));
        findId(number).andExpect(status().isOk()).andExpect(jsonPath("maskedEmail").value("se***@a.test"));
        assertThat(latest).startsWith("se");

        findId(businessNumber()).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"));
        findId("12-3").andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"))
                .andExpect(jsonPath("details.field").value("businessNumber"));
        post("/api/auth/find-id", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("businessNumber"));
    }

    @Test void resetFlowChangesPasswordLogsOutEverywhereAndTokenWorksOnce() throws Exception {
        String email = user("reset-" + UUID.randomUUID() + "@example.test", businessNumber());
        String access = access(login(email, OLD_PASSWORD).andExpect(status().isOk()).andReturn());

        post("/api/auth/password-reset/code", json(Map.of("email", email))).andExpect(status().isNoContent());
        assertThat(mail.sent.get()).isEqualTo(1);
        String code = mail.codes.get(email);

        String wrong = code.equals("000000") ? "111111" : "000000";
        post("/api/auth/password-reset/verify", json(Map.of("email", email, "code", wrong)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_CODE"))
                .andExpect(jsonPath("details.field").value("code"));
        String token = mapper.readTree(post("/api/auth/password-reset/verify", json(Map.of("email", email, "code", code)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("resetToken").stringValue();

        // 가입과 같은 비밀번호 규칙
        post("/api/auth/password-reset", json(Map.of("resetToken", token, "newPassword", "short")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"))
                .andExpect(jsonPath("details.field").value("newPassword"));
        post("/api/auth/password-reset", json(Map.of("resetToken", token, "newPassword", NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        login(email, OLD_PASSWORD).andExpect(status().isUnauthorized());
        login(email, NEW_PASSWORD).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
        // 토큰은 한 번만 써요.
        post("/api/auth/password-reset", json(Map.of("resetToken", token, "newPassword", "Again123!")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_TOKEN"));
        post("/api/auth/password-reset", json(Map.of("resetToken", "x".repeat(43), "newPassword", NEW_PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_TOKEN"));
    }

    @Test void unknownEmailLooksTheSameButSendsNothing() throws Exception {
        String unknown = "nobody-" + UUID.randomUUID() + "@example.test";
        emails.add(unknown);
        post("/api/auth/password-reset/code", json(Map.of("email", unknown))).andExpect(status().isNoContent());
        assertThat(mail.sent.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verifications WHERE email = ?", Integer.class, unknown)).isZero();
        post("/api/auth/password-reset/verify", json(Map.of("email", unknown, "code", "123456")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_CODE"));
    }

    @Test void secondRequestWithinAMinuteIsQuietAndFiveWrongCodesLockTheCode() throws Exception {
        String email = user("limit-" + UUID.randomUUID() + "@example.test", businessNumber());
        post("/api/auth/password-reset/code", json(Map.of("email", email))).andExpect(status().isNoContent());
        post("/api/auth/password-reset/code", json(Map.of("email", email))).andExpect(status().isNoContent());
        assertThat(mail.sent.get()).isEqualTo(1);

        String code = mail.codes.get(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            post("/api/auth/password-reset/verify", json(Map.of("email", email, "code", wrong)))
                    .andExpect(jsonPath("code").value("INVALID_CODE"));
        }
        post("/api/auth/password-reset/verify", json(Map.of("email", email, "code", code)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_CODE"));
    }

    @Test void signupVerificationStillRejectsRegisteredEmails() throws Exception {
        String email = user("signup-" + UUID.randomUUID() + "@example.test", businessNumber());
        post("/api/auth/email-verifications", json(Map.of("email", email))).andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("DUPLICATE_EMAIL"));
        assertThat(mail.sent.get()).isZero();
    }

    private String user(String email, String businessNumber) {
        jdbc.update("INSERT INTO users (email, password_hash, company_name, manager_name, business_reg_no) VALUES (?, ?, '회사', '담당자', ?)",
                email, encoder.encode(OLD_PASSWORD), businessNumber);
        emails.add(email);
        return email;
    }

    private static String businessNumber() {
        return String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }

    private String json(Map<String, String> body) throws Exception { return mapper.writeValueAsString(body); }

    private ResultActions findId(String number) throws Exception {
        return post("/api/auth/find-id", json(Map.of("businessNumber", number)));
    }

    private ResultActions post(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(realCsrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                .with(realCsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", email).param("password", password));
    }

    private String access(MvcResult login) throws Exception {
        return mapper.readTree(login.getResponse().getContentAsString()).get("accessToken").stringValue();
    }

    // spring-security-test의 csrf()는 같은 테스트 서버를 쓰는 다른 테스트의 CSRF 저장 방식을 바꿔 버려서, 실제 CSRF 쿠키를 받아 보낸다.
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
