package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.service.EmailVerificationService;
import com.ipgo.server.support.TestMailConfiguration;
import com.ipgo.server.support.UploadTestDatabase;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #172 회원 탈퇴: 비밀번호 확인 → 구독 멈춤 · 세션 폐기 · 개인정보 지우기. 같은 이메일로 바로 다시 가입할 수 있다.
@AutoConfigureMockMvc
@Import(TestMailConfiguration.class)
@org.springframework.test.context.TestPropertySource(properties = {"ipgo.mail.from=noreply@example.test", "management.health.mail.enabled=false"})
class AccountWithdrawalIntegrationTests extends DatabaseIntegrationTest {
    static final String PASSWORD = "Test123!";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired EmailVerificationService verifications;
    @Autowired TestMailConfiguration.RecordingMailSender mail;
    final List<Long> created = new ArrayList<>();
    String email;

    @BeforeEach void setup() {
        UploadTestDatabase.withdrawalSchema(jdbc);
        mail.reset();
        email = "withdraw-" + UUID.randomUUID() + "@example.test";
    }

    @AfterEach void cleanup() {
        for (long id : created) {
            jdbc.update("DELETE FROM billing_subscriptions WHERE user_id = ?", id);
            jdbc.update("DELETE FROM products WHERE user_id = ?", id);
            jdbc.update("DELETE FROM warehouses WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM email_verifications WHERE email = ?", email);
    }

    @Test void wrongOrBlankPasswordKeepsTheAccount() throws Exception {
        long id = signup();
        String access = access(login(email).andExpect(status().isOk()).andReturn());

        withdraw(access, "{\"password\": \"Wrong123!\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("WRONG_PASSWORD")).andExpect(jsonPath("details.field").value("password"));
        withdraw(access, "{\"password\": \"\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_INPUT")).andExpect(jsonPath("details.field").value("password"));
        withdraw(access, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("password"));

        me(access).andExpect(status().isOk()).andExpect(jsonPath("email").value(email));
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM users WHERE id = ?", Object.class, id)).isNull();
    }

    @Test void requiresLogin() throws Exception {
        mvc.perform(post("/api/account/withdraw").with(realCsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\": \"" + PASSWORD + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test void withdrawalLogsOutEverywhereErasesPersonalDataAndAllowsImmediateResignupTwice() throws Exception {
        long first = signup();
        jdbc.update("INSERT INTO warehouses (user_id, name) VALUES (?, '기본 창고')", first);
        jdbc.update("INSERT INTO products (user_id, sku_barcode, sku_name) VALUES (?, 'A', '상품A')", first);
        MvcResult loggedIn = login(email).andExpect(status().isOk()).andReturn();
        String access = access(loggedIn);
        Cookie refresh = loggedIn.getResponse().getCookie("ipgo_refresh");
        String oldHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, first);

        MvcResult done = withdraw(access, "{\"password\": \"" + PASSWORD + "\"}").andExpect(status().isNoContent()).andReturn();
        Cookie cleared = done.getResponse().getCookie("ipgo_refresh");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();

        // 이미 받은 토큰 · refresh 쿠키 · 옛 이메일 로그인 모두 바로 막혀요.
        me(access).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(realCsrf()).cookie(refresh)).andExpect(status().isUnauthorized());
        login(email).andExpect(status().isUnauthorized());

        // 개인정보는 지우고, 결제 원장 · 보관 원본이 가리킬 수 있는 회원 행과 업무 데이터는 남아요.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT email, password_hash, company_name, manager_name, business_reg_no, withdrawn_at FROM users WHERE id = ?", first);
        assertThat(row.get("email")).isEqualTo("withdrawn-" + first + "@withdrawn.invalid");
        assertThat(row.get("password_hash")).isNotEqualTo(oldHash);
        assertThat(row.get("company_name")).isNull();
        assertThat(row.get("manager_name")).isNull();
        assertThat(row.get("business_reg_no")).isNull();
        assertThat(row.get("withdrawn_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE user_id = ?", Integer.class, first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE user_id = ? AND revoked = FALSE",
                Integer.class, first)).isZero();

        // 같은 이메일 · 같은 사업자번호로 바로 다시 가입하면 새 계정이고, 옛 데이터는 안 보여요.
        long second = signup();
        assertThat(second).isNotEqualTo(first);
        String secondAccess = access(login(email).andExpect(status().isOk()).andReturn());
        me(secondAccess).andExpect(status().isOk()).andExpect(jsonPath("userId").value(second));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE user_id = ?", Integer.class, second)).isZero();

        // 두 번째 탈퇴도 회원 번호가 달라서 바뀐 이메일이 겹치지 않아요.
        withdraw(secondAccess, "{\"password\": \"" + PASSWORD + "\"}").andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, second))
                .isEqualTo("withdrawn-" + second + "@withdrawn.invalid");
    }

    @Test void stopsTheNextChargeEvenWhenBillingIsSwitchedOff() throws Exception {
        long id = signup();
        jdbc.update("""
                INSERT INTO billing_subscriptions (user_id, merchant_id, mode, status, plan_id, price_version, amount,
                    trial_end, period_start, period_end, anchor_at, cycle_no, customer_key, next_charge_at)
                VALUES (?, 'test-merchant', 'TEST', 'ACTIVE', 'seller', 'test', 49000, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30' DAY, CURRENT_TIMESTAMP, 1, ?, CURRENT_TIMESTAMP + INTERVAL '30' DAY)
                """, id, "withdraw-test-" + UUID.randomUUID());
        String access = access(login(email).andExpect(status().isOk()).andReturn());

        withdraw(access, "{\"password\": \"" + PASSWORD + "\"}").andExpect(status().isNoContent());

        Map<String, Object> subscription = jdbc.queryForMap(
                "SELECT cancel_at_period_end, next_charge_at FROM billing_subscriptions WHERE user_id = ?", id);
        assertThat(subscription.get("cancel_at_period_end")).isEqualTo(true);
        assertThat(subscription.get("next_charge_at")).isNull();
    }

    // spring-security-test의 csrf()는 같은 테스트 서버를 쓰는 다른 테스트의 CSRF 저장 방식을 세션으로 바꿔 버린다.
    // 가입 테스트처럼 실제 CSRF 쿠키를 받아 보낸다.
    private org.springframework.test.web.servlet.request.RequestPostProcessor realCsrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var json = mapper.readTree(result.getResponse().getContentAsString());
        Cookie cookie = result.getResponse().getCookie("IPGO-XSRF-TOKEN");
        String header = json.get("headerName").stringValue(), token = json.get("token").stringValue();
        return request -> {
            Cookie[] old = request.getCookies() == null ? new Cookie[0] : request.getCookies();
            Cookie[] all = java.util.Arrays.copyOf(old, old.length + 1);
            all[old.length] = cookie;
            request.setCookies(all);
            request.addHeader(header, token);
            return request;
        };
    }

    private long signup() throws Exception {
        // 인증 메일은 1분에 한 번이라, 같은 테스트 안에서 다시 가입할 때는 지난 인증 기록을 지운다(실제로는 1분 뒤 재가입).
        jdbc.update("DELETE FROM email_verifications WHERE email = ?", email);
        verifications.requestCode(email);
        String token = verifications.confirm(email, mail.codes.get(email));
        mail.reset();
        String body = mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD, "companyName", "테스트 회사",
                "managerName", "담당자", "businessRegNo", "1234567890", "verificationToken", token));
        MvcResult result = mvc.perform(post("/api/auth/signup").with(realCsrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        long id = mapper.readTree(result.getResponse().getContentAsString()).get("userId").longValue();
        created.add(id);
        return id;
    }

    private ResultActions login(String loginEmail) throws Exception {
        return mvc.perform(post("/api/auth/login").with(realCsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", loginEmail).param("password", PASSWORD));
    }

    private String access(MvcResult login) throws Exception {
        return mapper.readTree(login.getResponse().getContentAsString()).get("accessToken").stringValue();
    }

    private ResultActions me(String access) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access));
    }

    private ResultActions withdraw(String access, String body) throws Exception {
        return mvc.perform(post("/api/account/withdraw").with(realCsrf()).header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
