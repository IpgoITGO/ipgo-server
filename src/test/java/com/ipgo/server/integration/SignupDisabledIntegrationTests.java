package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.entity.UserAccount;
import com.ipgo.server.repository.EmailVerificationRepository;
import com.ipgo.server.repository.UserAccountRepository;
import com.ipgo.server.support.TestMailConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestMailConfiguration.class)
@TestPropertySource(properties = {"ipgo.signup.enabled=false", "ipgo.mail.from=noreply@example.test",
        "management.health.mail.enabled=false"})
@AutoConfigureMockMvc
class SignupDisabledIntegrationTests extends DatabaseIntegrationTest {
    private static final String NEW_EMAIL = "closed-signup@example.test";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired UserAccountRepository users;
    @Autowired EmailVerificationRepository verifications;
    @Autowired PasswordEncoder encoder;
    @Autowired TestMailConfiguration.RecordingMailSender mail;

    @Test
    void deniesSignupAndSignupVerificationWithoutCreatingStateOrSendingMail() throws Exception {
        mail.reset();
        denied(mvc.perform(get("/api/auth/email-availability").param("email", NEW_EMAIL)));
        denied(mvc.perform(csrf(post("/api/auth/email-verifications")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + NEW_EMAIL + "\"}")));
        denied(mvc.perform(csrf(post("/api/auth/email-verifications/confirm")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + NEW_EMAIL + "\",\"code\":\"123456\"}")));
        denied(mvc.perform(csrf(post("/api/auth/signup")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"Test123!","companyName":"QA","managerName":"QA","verificationToken":"%s"}
                        """.formatted(NEW_EMAIL, "x".repeat(43)))));
        assertThat(users.existsByEmail(NEW_EMAIL)).isFalse();
        assertThat(verifications.existsById(NEW_EMAIL)).isFalse();
        assertThat(mail.sent.get()).isZero();
    }

    @Test
    void existingAccountCanStillLoginAndRequestPasswordRecovery() throws Exception {
        String email = "existing-signup-gate-qa@example.test";
        String password = "Test123!";
        users.saveAndFlush(new UserAccount(email, encoder.encode(password), "QA", "QA", null));
        mvc.perform(csrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", email).param("password", password)).andExpect(status().isOk())
                .andExpect(jsonPath("accessToken").isString());
        mail.reset();
        mvc.perform(csrf(post("/api/auth/password-reset/code")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andExpect(status().isNoContent());
        assertThat(mail.sent.get()).isEqualTo(1);
    }

    private void denied(ResultActions response) throws Exception {
        response.andExpect(status().isForbidden()).andExpect(jsonPath("code").value("SIGNUP_DISABLED"));
    }

    private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsString());
        Cookie cookie = response.getCookie("IPGO-XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return request.cookie(cookie).header(json.get("headerName").stringValue(), json.get("token").stringValue());
    }
}
