package com.ipgo.server.auth;

import java.util.Map;
import com.ipgo.server.DatabaseIntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthIntegrationTests extends DatabaseIntegrationTest {
    private static final String EMAIL = "seller@example.test";
    private static final String PASSWORD = "test-only-password-123!";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DataSource dataSource;
    @Autowired PersonalDataCrypto crypto;

    @BeforeEach
    void clearTestDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
        users.deleteAll();
    }

    @Test
    void signupNormalizesEmailAndStoresOnlyPasswordHash() throws Exception {
        signup("Seller@Example.test", PASSWORD, csrf(null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("email").value(EMAIL))
                .andExpect(jsonPath("id").isNumber())
                .andExpect(jsonPath("createdAt").isString())
                .andExpect(jsonPath("password").doesNotExist())
                .andExpect(jsonPath("passwordHash").doesNotExist());
        UserAccount user = users.findByEmailLookupHash(crypto.emailLookup(EMAIL)).orElseThrow();
        assertThat(user.getEncryptedEmail()).startsWith("v1:").doesNotContain(EMAIL);
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("{pbkdf2-sha256-600k}");
        assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
    }

    @Test
    void rejectsDuplicateEmailRegardlessOfCase() throws Exception {
        signup(EMAIL, PASSWORD, csrf(null)).andExpect(status().isCreated());
        signup("SELLER@example.test", PASSWORD, csrf(null))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("DUPLICATE_EMAIL"));
        assertThat(users.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "seller@", "@example.test"})
    void rejectsInvalidEmail(String email) throws Exception {
        signup(email, PASSWORD, csrf(null)).andExpect(status().isBadRequest());
        assertThat(users.count()).isZero();
    }

    @Test
    void rejectsShortAndOversizePasswordsWithoutLeakingThem() throws Exception {
        for (String password : new String[] {"short", "x".repeat(129)}) {
            var result = signup(EMAIL, password, csrf(null))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(password);
        }
        assertThat(users.count()).isZero();
    }

    @Test
    void rejectsMalformedSignupJson() throws Exception {
        CsrfSession token = csrf(null);
        mvc.perform(post("/api/auth/signup").session(token.session())
                        .header(token.header(), token.token()).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"));
    }

    @Test
    void loginRotatesSessionAndLogoutRevokesAuthentication() throws Exception {
        CsrfSession before = csrf(null);
        signup(EMAIL, PASSWORD, before).andExpect(status().isCreated());
        String previousId = before.session().getId();
        MvcResult result = login(" SELLER@example.test ", PASSWORD, before)
                .andExpect(status().isNoContent()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(previousId);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("email").value(EMAIL));
        mvc.perform(post("/api/auth/logout").session(session).header(before.header(), before.token()))
                .andExpect(status().isForbidden());
        CsrfSession after = csrf(session);
        String authenticatedId = session.getId();
        mvc.perform(post("/api/auth/logout").session(session).header(after.header(), after.token()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie("JSESSIONID", authenticatedId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongPasswordAndUnknownAccountWithSameResponse() throws Exception {
        signup(EMAIL, PASSWORD, csrf(null)).andExpect(status().isCreated());
        String wrongPassword = login(EMAIL, "wrong-password", csrf(null))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownAccount = login("unknown@example.test", PASSWORD, csrf(null))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(wrongPassword).isEqualTo(unknownAccount).doesNotContain(PASSWORD);
    }

    @Test
    void stateChangingEndpointsRequireCsrf() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").param("email", EMAIL).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
        assertThat(users.count()).isZero();
    }

    @Test
    void meUsesAuthenticatedUserEvenWhenAnotherIdIsSupplied() throws Exception {
        signup(EMAIL, PASSWORD, csrf(null)).andExpect(status().isCreated());
        signup("another@example.test", PASSWORD, csrf(null)).andExpect(status().isCreated());
        long anotherId = users.findByEmailLookupHash(crypto.emailLookup("another@example.test")).orElseThrow().getId();
        var session = (MockHttpSession) login(EMAIL, PASSWORD, csrf(null))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session).param("userId", Long.toString(anotherId)))
                .andExpect(status().isOk()).andExpect(jsonPath("email").value(EMAIL));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectsOtherEndpointsAndExposesHealthWithoutDetails() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("UP"))
                .andExpect(jsonPath("components").doesNotExist());
    }

    @Test
    void internalErrorsAreNotDisguisedAsAuthenticationFailures() throws Exception {
        mvc.perform(get("/error").with(request -> {
                            request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
                            return request;
                        })
                        .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 500))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("trace").doesNotExist());
        mvc.perform(get("/error")).andExpect(status().isUnauthorized());
    }

    private CsrfSession csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        return new CsrfSession((MockHttpSession) result.getRequest().getSession(false),
                json.get("headerName").stringValue(), json.get("token").stringValue());
    }

    private ResultActions signup(String email, String password, CsrfSession token) throws Exception {
        return mvc.perform(post("/api/auth/signup").session(token.session())
                .header(token.header(), token.token()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions login(String email, String password, CsrfSession token) throws Exception {
        return mvc.perform(post("/api/auth/login").session(token.session())
                .header(token.header(), token.token()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .with(request -> {
                    request.setRequestedSessionId(token.session().getId());
                    request.setRequestedSessionIdValid(true);
                    return request;
                })
                .param("email", email).param("password", password));
    }

    private record CsrfSession(MockHttpSession session, String header, String token) {
    }
}
