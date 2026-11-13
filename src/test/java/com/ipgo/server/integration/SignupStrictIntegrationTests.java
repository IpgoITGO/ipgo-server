package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.service.EmailVerificationService;
import com.ipgo.server.support.TestMailConfiguration;
import com.ipgo.server.support.UploadTestDatabase;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
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
import org.springframework.test.context.TestPropertySource;
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

// #206 프론트 배포 뒤(strict=true): 동의와 사업자등록번호(검증 숫자 포함)가 필수다.
@AutoConfigureMockMvc
@Import(TestMailConfiguration.class)
@TestPropertySource(properties = {"ipgo.mail.from=noreply@example.test", "management.health.mail.enabled=false",
        "ipgo.signup.strict=true"})
class SignupStrictIntegrationTests extends DatabaseIntegrationTest {
    static final Map<String, Object> AGREED = Map.of("terms", true, "privacy", true, "version", "2026-11-v1");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired EmailVerificationService verifications;
    @Autowired TestMailConfiguration.RecordingMailSender mail;
    final List<String> emails = new ArrayList<>();

    @BeforeEach void setup() {
        UploadTestDatabase.withdrawalSchema(jdbc);
        mail.reset();
    }

    @AfterEach void cleanup() {
        for (String email : emails) {
            jdbc.update("DELETE FROM users WHERE email = ?", email);
            jdbc.update("DELETE FROM email_verifications WHERE email = ?", email);
        }
    }

    @Test void agreementsAndValidBusinessNumberAreRequired() throws Exception {
        signup(Map.of("businessRegNo", "1248100998")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("agreements"));
        signup(Map.of("agreements", AGREED)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("businessRegNo"));
        signup(Map.of("agreements", AGREED, "businessRegNo", "1234567890")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_INPUT")).andExpect(jsonPath("details.field").value("businessRegNo"));

        signup(Map.of("agreements", AGREED, "businessRegNo", "1248100998")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT business_reg_no FROM users WHERE email = ?", String.class, emails.getLast()))
                .isEqualTo("1248100998");
    }

    private ResultActions signup(Map<String, Object> extra) throws Exception {
        String email = "strict-" + UUID.randomUUID() + "@example.test";
        emails.add(email);
        verifications.requestCode(email);
        String token = verifications.confirm(email, mail.codes.get(email));
        mail.reset();
        Map<String, Object> body = new HashMap<>(Map.of("email", email, "password", "Test123!", "companyName", "테스트 회사",
                "managerName", "담당자", "verificationToken", token));
        body.putAll(extra);
        return mvc.perform(post("/api/auth/signup").with(realCsrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
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
