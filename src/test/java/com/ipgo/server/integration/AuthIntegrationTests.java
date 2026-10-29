package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.entity.UserAccount;
import com.ipgo.server.repository.EmailVerificationRepository;
import com.ipgo.server.repository.UserAccountRepository;
import com.ipgo.server.service.EmailVerificationService;
import com.ipgo.server.support.TestMailConfiguration;
import com.ipgo.server.util.EmailNormalizer;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import jakarta.servlet.http.Cookie;
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

@org.springframework.context.annotation.Import(TestMailConfiguration.class)
@org.springframework.test.context.TestPropertySource(properties = {"ipgo.mail.from=noreply@example.test", "management.health.mail.enabled=false"})
@AutoConfigureMockMvc
class AuthIntegrationTests extends DatabaseIntegrationTest {
    private static final String EMAIL = "seller@example.test";
    private static final String PASSWORD = "Test123!";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DataSource dataSource;
    @Autowired EmailVerificationRepository verifications;
    @Autowired EmailVerificationService verificationService;
    @Autowired TestMailConfiguration.RecordingMailSender mail;
    private final Map<String, String> verificationTokens = new java.util.HashMap<>();

    @BeforeEach
    void clearTestDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            // 회원을 모두 지우므로 H2 메모리 DB나, 테스트용으로 직접 지정한 DB(IPGO_TEST_DB_URL)에서만 돈다.
            String url = connection.getMetaData().getURL();
            String testDb = System.getenv("IPGO_TEST_DB_URL");
            assertThat(url.startsWith("jdbc:h2:mem:") || (testDb != null && !testDb.isBlank() && url.equals(testDb)))
                    .as("테스트 DB가 아닌 곳에서 회원 삭제 테스트를 막습니다: " + url).isTrue();
        }
        users.deleteAll();
        verifications.deleteAll();
        verificationTokens.clear();
        mail.reset();
    }

    @Test
    void signupNormalizesEmailAndStoresOnlyPasswordHash() throws Exception {
        signup("Seller@Example.test", PASSWORD, csrf())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("email").value(EMAIL))
                .andExpect(jsonPath("userId").isNumber())
                .andExpect(jsonPath("id").doesNotExist())
                .andExpect(jsonPath("createdAt").doesNotExist())
                .andExpect(jsonPath("managerName").value("테스트 담당자"))
                .andExpect(jsonPath("companyName").value("테스트 회사"))
                .andExpect(jsonPath("password").doesNotExist())
                .andExpect(jsonPath("passwordHash").doesNotExist());
        UserAccount user = users.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("{pbkdf2-sha256-600k}");
        assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
    }

    @Test
    void rejectsDuplicateEmailRegardlessOfCase() throws Exception {
        signup(EMAIL, PASSWORD, csrf()).andExpect(status().isCreated());
        signup("SELLER@example.test", PASSWORD, csrf())
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("DUPLICATE_EMAIL"));
        assertThat(users.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "seller@", "@example.test"})
    void rejectsInvalidEmail(String email) throws Exception {
        signup(email, PASSWORD, csrf()).andExpect(status().isBadRequest());
        assertThat(users.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Aa1!aaaa", "Aa1!aaaaa", "Aa1!aaaaaa", "Aa1\"\\[]!"})
    void acceptsPasswordsWithinPolicyAndAllowsLogin(String password) throws Exception {
        signup(EMAIL, password, csrf()).andExpect(status().isCreated());
        login(EMAIL, password, csrf()).andExpect(status().isOk());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Aa1!aaa", "Aa1!aaaaaaa", "aa1!aaaa", "AA1!AAAA", "Aa!!aaaa", "Aa12aaaa",
            " Aa1!aaa", "Aa1!aaa ", "Aa1! aaa", "Aa1!aaa\n", "Aa1!aa\ta", "Aa1!가aaa", "Aa1!😀aa",
            "Aa!aaaa１"})
    void rejectsInvalidPasswordsWithoutLeakingThemOrConsumingVerificationToken(String password) throws Exception {
        var result = signup(EMAIL, password, csrf())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_INPUT"))
                .andExpect(jsonPath("details.field").value("password"))
                .andExpect(jsonPath("message").value(
                        "비밀번호는 8~10자로 영문 대문자, 소문자, 숫자, 특수기호를 각각 1개 이상 포함해야 하며, 공백 없이 ASCII 문자만 사용할 수 있습니다."))
                .andReturn();
        if (password != null && !password.isEmpty()) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(password);
        }
        assertThat(users.count()).isZero();
        signup(EMAIL, PASSWORD, csrf()).andExpect(status().isCreated());
    }

    @Test
    void existingAccountCanStillLoginWithPasswordOutsideNewSignupPolicy() throws Exception {
        String legacyPassword = "test-only-password-123!";
        users.saveAndFlush(new UserAccount(EMAIL, passwordEncoder.encode(legacyPassword),
                "테스트 회사", "테스트 담당자", null));
        login(EMAIL, legacyPassword, csrf()).andExpect(status().isOk());
    }

    @Test
    void rejectsMalformedSignupJson() throws Exception {
        CsrfSession token = csrf();
        mvc.perform(post("/api/auth/signup").cookie(token.cookie())
                        .header(token.header(), token.token()).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"));
    }

    @Test
    void loginReturnsBearerAndLogoutRevokesItWithoutCreatingHttpSession() throws Exception {
        CsrfSession before = csrf();
        signup(EMAIL, PASSWORD, before).andExpect(status().isCreated());
        MvcResult result = login(" SELLER@example.test ", PASSWORD, before)
                .andExpect(status().isOk()).andExpect(jsonPath("tokenType").value("Bearer"))
                .andExpect(jsonPath("expiresIn").value(900)).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        String access = mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();
        Cookie refresh = result.getResponse().getCookie("ipgo_refresh");
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("email").value(EMAIL))
                .andExpect(jsonPath("managerName").value("테스트 담당자"))
                .andExpect(jsonPath("companyName").value("테스트 회사"));
        // 쿠키만으로는 일반 API를 인증할 수 없다.
        mvc.perform(get("/api/auth/me").cookie(refresh)).andExpect(status().isUnauthorized());
        CsrfSession after = csrf();
        mvc.perform(post("/api/auth/logout").cookie(refresh, after.cookie())
                        .header(before.header(), before.token()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(refresh, after.cookie())
                        .header(after.header(), after.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("SESSION_REVOKED"));
    }

    @Test
    void rejectsWrongPasswordAndUnknownAccountWithSameResponse() throws Exception {
        signup(EMAIL, PASSWORD, csrf()).andExpect(status().isCreated());
        String wrongPassword = login(EMAIL, "wrong-password", csrf())
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownAccount = login("unknown@example.test", PASSWORD, csrf())
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
        signup(EMAIL, PASSWORD, csrf()).andExpect(status().isCreated());
        signup("another@example.test", PASSWORD, csrf()).andExpect(status().isCreated());
        long anotherId = users.findByEmail("another@example.test").orElseThrow().getId();
        var login = login(EMAIL, PASSWORD, csrf()).andExpect(status().isOk()).andReturn();
        String access = mapper.readTree(login.getResponse().getContentAsString()).get("accessToken").stringValue();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access).param("userId", Long.toString(anotherId)))
                .andExpect(status().isOk()).andExpect(jsonPath("email").value(EMAIL))
                .andExpect(jsonPath("userId").isNumber())
                .andExpect(jsonPath("managerName").value("테스트 담당자"))
                .andExpect(jsonPath("companyName").value("테스트 회사"));
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

    private CsrfSession csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(result.getRequest().getSession(false)).isNull();
        return new CsrfSession(result.getResponse().getCookie("IPGO-XSRF-TOKEN"),
                json.get("headerName").stringValue(), json.get("token").stringValue());
    }

    private ResultActions signup(String email, String password, CsrfSession token) throws Exception {
        String normalized = EmailNormalizer.normalize(email);
        String verificationToken = "x".repeat(43);
        if (normalized.endsWith("@example.test") && !normalized.startsWith("@") && !users.existsByEmail(normalized)) {
            verificationToken = verificationTokens.computeIfAbsent(normalized, key -> {
                verificationService.requestCode(key);
                return verificationService.confirm(key, mail.codes.get(key));
            });
        }
        var body = new java.util.HashMap<String, Object>(Map.of("email", email,
                "companyName", "테스트 회사", "managerName", "테스트 담당자", "verificationToken", verificationToken));
        body.put("password", password);
        return mvc.perform(post("/api/auth/signup").cookie(token.cookie())
                .header(token.header(), token.token()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private ResultActions login(String email, String password, CsrfSession token) throws Exception {
        return mvc.perform(post("/api/auth/login").cookie(token.cookie())
                .header(token.header(), token.token()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", email).param("password", password));
    }

    private record CsrfSession(Cookie cookie, String header, String token) {
    }
}
