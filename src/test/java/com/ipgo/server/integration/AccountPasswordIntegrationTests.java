package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #192 로그인한 상태에서 비밀번호 바꾸기. 지금 화면은 로그인을 유지하고 다른 기기만 로그아웃된다.
@AutoConfigureMockMvc
class AccountPasswordIntegrationTests extends DatabaseIntegrationTest {
    static final String OLD_PASSWORD = "Test123!";
    static final String NEW_PASSWORD = "Newpass9#";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder encoder;
    final List<String> emails = new ArrayList<>();

    @BeforeEach void setup() { UploadTestDatabase.withdrawalSchema(jdbc); }

    @AfterEach void cleanup() {
        for (String email : emails) jdbc.update("DELETE FROM users WHERE email = ?", email);
    }

    @Test void changeKeepsThisScreenLoggedInAndLogsOutOtherDevices() throws Exception {
        String email = user();
        String here = access(login(email, OLD_PASSWORD).andExpect(status().isOk()).andReturn());
        MvcResult other = login(email, OLD_PASSWORD).andExpect(status().isOk()).andReturn();
        String otherAccess = access(other);
        Cookie otherRefresh = other.getResponse().getCookie("ipgo_refresh");

        change(here, OLD_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        me(here).andExpect(status().isOk()).andExpect(jsonPath("email").value(email));
        me(otherAccess).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(realCsrf()).cookie(otherRefresh)).andExpect(status().isUnauthorized());
        login(email, OLD_PASSWORD).andExpect(status().isUnauthorized());
        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test void wrongCurrentPasswordOrBadNewPasswordChangesNothing() throws Exception {
        String email = user();
        String access = access(login(email, OLD_PASSWORD).andExpect(status().isOk()).andReturn());
        String other = access(login(email, OLD_PASSWORD).andExpect(status().isOk()).andReturn());

        change(access, "Wrong123!", NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("WRONG_PASSWORD")).andExpect(jsonPath("details.field").value("currentPassword"));
        change(access, "", NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_INPUT")).andExpect(jsonPath("details.field").value("currentPassword"));
        // 가입과 같은 규칙: 8~16자, 영문 대·소문자 · 숫자 · 특수문자
        for (String weak : List.of("short1!", "nouppercase1!", "NoSpecial123", "Toolongpassword1!")) {
            change(access, OLD_PASSWORD, weak).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("code").value("INVALID_INPUT")).andExpect(jsonPath("details.field").value("newPassword"));
        }

        login(email, OLD_PASSWORD).andExpect(status().isOk());
        me(other).andExpect(status().isOk());
    }

    @Test void withdrawnAccountCannotChange() throws Exception {
        String email = user();
        String access = access(login(email, OLD_PASSWORD).andExpect(status().isOk()).andReturn());
        // 탈퇴 처리와 거의 같은 순간에 들어온 요청처럼, 세션은 살아 있지만 회원은 탈퇴한 상태
        jdbc.update("UPDATE users SET withdrawn_at = CURRENT_TIMESTAMP WHERE email = ?", email);
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);

        change(access, OLD_PASSWORD, NEW_PASSWORD).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email)).isEqualTo(hash);
    }

    @Test void requiresLogin() throws Exception {
        mvc.perform(post("/api/account/password").with(realCsrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json(OLD_PASSWORD, NEW_PASSWORD))).andExpect(status().isUnauthorized());
    }

    private String user() {
        String email = "password-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, company_name, manager_name) VALUES (?, ?, '회사', '담당자')",
                email, encoder.encode(OLD_PASSWORD));
        emails.add(email);
        return email;
    }

    private String json(String current, String next) throws Exception {
        return mapper.writeValueAsString(Map.of("currentPassword", current, "newPassword", next));
    }

    private ResultActions change(String access, String current, String next) throws Exception {
        return mvc.perform(post("/api/account/password").with(realCsrf()).header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content(json(current, next)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(realCsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", email).param("password", password));
    }

    private String access(MvcResult login) throws Exception {
        return mapper.readTree(login.getResponse().getContentAsString()).get("accessToken").stringValue();
    }

    private ResultActions me(String access) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access));
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
