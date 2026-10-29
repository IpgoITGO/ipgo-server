package com.ipgo.server.auth;

import com.ipgo.server.DatabaseIntegrationTest;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class TokenIntegrationTests extends DatabaseIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired TokenService tokens;
    @Autowired JwtTokens jwt;
    @Autowired AuthSessionRepository sessions;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${ipgo.auth.jwt-secret}") String secret;
    @MockitoBean Clock clock;
    private long userId;

    @BeforeEach
    void setup() {
        users.deleteAll();
        when(clock.instant()).thenReturn(NOW);
        userId = users.saveAndFlush(new UserAccount("jwt@example.test", passwords.encode("Test123!"),
                "테스트 회사", "담당자", null)).getId();
    }

    @Test
    void deletingAnAccountCascadesToItsLoginAndRefreshHistory() {
        var first = tokens.login(userId);
        tokens.refresh(first.refreshToken());
        assertThat(refreshTokens.count()).isEqualTo(2);
        users.deleteById(userId);
        assertThat(sessions.count()).isZero();
        assertThat(refreshTokens.count()).isZero();
        assertThatThrownBy(() -> jwt.decode(first.access().accessToken())).isInstanceOf(JwtException.class);
    }

    @Test
    void loginReturnsShortLivedJwtAndHttpOnlyCookieWhileDatabaseStoresOnlyHashes() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "jwt@example.test").param("password", "Test123!"))
                .andExpect(status().isOk()).andExpect(jsonPath("expiresIn").value(900))
                .andExpect(jsonPath("tokenType").value("Bearer"))
                .andExpect(jsonPath("refreshToken").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        Cookie cookie = result.getResponse().getCookie("ipgo_refresh");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getMaxAge()).isEqualTo(604800);
        assertThat(result.getRequest().getSession(false)).isNull();
        String access = mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();
        Jwt decoded = jwt.decode(access);
        assertThat(decoded.getSubject()).isEqualTo(Long.toString(userId));
        assertThat(decoded.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(decoded.getClaims()).doesNotContainKeys("email", "password", "refreshToken");
        assertThat(refreshTokens.findAll()).hasSize(1).allSatisfy(stored ->
                assertThat(stored.getTokenHash()).matches("[a-f0-9]{64}").isNotEqualTo(cookie.getValue()));
        me(access).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void accessExpiresAtFifteenMinutesAndRefreshMakesProtectedRequestsWorkAgain() throws Exception {
        var first = tokens.login(userId);
        when(clock.instant()).thenReturn(NOW.plusSeconds(899));
        me(first.access().accessToken()).andExpect(status().isOk());
        when(clock.instant()).thenReturn(NOW.plusSeconds(900));
        me(first.access().accessToken()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value("ACCESS_TOKEN_EXPIRED"));
        var result = refresh(first.refreshToken(), first.access().accessToken())
                .andExpect(status().isOk()).andReturn();
        String next = mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();
        me(next).andExpect(status().isOk());
        assertThat(result.getResponse().getCookie("ipgo_refresh").getValue()).isNotEqualTo(first.refreshToken());
        assertThat(result.getResponse().getCookie("ipgo_refresh").getMaxAge()).isEqualTo(604800 - 900);
    }

    @Test
    void refreshNeverExtendsTheOriginalSevenDayDeadline() throws Exception {
        var first = tokens.login(userId);
        when(clock.instant()).thenReturn(NOW.plusSeconds(604800 - 30));
        var result = refresh(first.refreshToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("expiresIn").value(30)).andReturn();
        var next = result.getResponse().getCookie("ipgo_refresh");
        assertThat(next.getMaxAge()).isEqualTo(30);
        assertThat(sessions.findAll()).singleElement().satisfies(s ->
                assertThat(s.getExpiresAt()).isEqualTo(NOW.plusSeconds(604800)));
        when(clock.instant()).thenReturn(NOW.plusSeconds(604800));
        refresh(next.getValue(), null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value("REFRESH_TOKEN_EXPIRED"))
                .andExpect(cookie().maxAge("ipgo_refresh", 0));
    }

    @Test
    void reusingAnOldRefreshTokenRevokesTheEntireLoginButNotOtherLogins() throws Exception {
        var first = tokens.login(userId);
        var otherLogin = tokens.login(userId);
        var next = tokens.refresh(first.refreshToken());
        refresh(first.refreshToken(), null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value("REFRESH_TOKEN_REUSED"));
        refresh(next.refreshToken(), null).andExpect(status().isUnauthorized());
        me(first.access().accessToken()).andExpect(status().isUnauthorized());
        me(next.access().accessToken()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value("SESSION_REVOKED"));
        me(otherLogin.access().accessToken()).andExpect(status().isOk());
    }

    @Test
    void logoutImmediatelyRevokesEveryAccessTokenOfTheLoginAndDeletesCookie() throws Exception {
        var first = tokens.login(userId);
        var next = tokens.refresh(first.refreshToken());
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(new Cookie("ipgo_refresh", next.refreshToken())))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("ipgo_refresh", 0));
        me(first.access().accessToken()).andExpect(status().isUnauthorized());
        me(next.access().accessToken()).andExpect(status().isUnauthorized());
        refresh(next.refreshToken(), null).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void logoutStillWorksWithAnExpiredAccessToken() throws Exception {
        var first = tokens.login(userId);
        when(clock.instant()).thenReturn(NOW.plusSeconds(901));
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(new Cookie("ipgo_refresh", first.refreshToken()))
                        .header("Authorization", "Bearer " + first.access().accessToken()))
                .andExpect(status().isNoContent());
        refresh(first.refreshToken(), null).andExpect(status().isUnauthorized());
    }

    @Test
    void cookieEndpointsRequireCsrfEvenWithAValidBearerToken() throws Exception {
        var first = tokens.login(userId);
        for (String path : List.of("/api/auth/refresh", "/api/auth/logout", "/api/auth/login")) {
            mvc.perform(post(path).cookie(new Cookie("ipgo_refresh", first.refreshToken()))
                            .header("Authorization", "Bearer " + first.access().accessToken())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("email", "jwt@example.test").param("password", "Test123!"))
                    .andExpect(status().isForbidden());
        }
        me(first.access().accessToken()).andExpect(status().isOk());
        // 일반 API는 Authorization 헤더로 인증하므로 CSRF 쿠키 없이도 사용한다.
        mvc.perform(put("/api/replenishment-settings").header("Authorization", "Bearer " + first.access().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadTimeDays\":5,\"reviewCycleDays\":7,\"orderUnitQty\":1}"))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM warehouses WHERE user_id = ?", userId);
    }

    @Test
    void missingMalformedAndUnknownRefreshTokensAreRejectedWithoutLeakingThem() throws Exception {
        mvc.perform(post("/api/auth/refresh").with(csrf())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value("INVALID_REFRESH_TOKEN"));
        for (String raw : List.of("invalid-token", "x".repeat(43))) {
            var result = refresh(raw, null).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("code").value("INVALID_REFRESH_TOKEN")).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(raw);
        }
    }

    @Test
    void rejectsTamperedUnsignedAndIncorrectClaims() throws Exception {
        var issued = tokens.login(userId);
        String access = issued.access().accessToken();
        int signature = access.lastIndexOf('.') + 1;
        String tampered = access.substring(0, signature) + (access.charAt(signature) == 'A' ? 'B' : 'A')
                + access.substring(signature + 1);
        me(tampered).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_ACCESS_TOKEN"));
        String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
                + access.substring(access.indexOf('.'), access.lastIndexOf('.') + 1);
        me(unsigned).andExpect(status().isUnauthorized());
        List<Consumer<Map<String, Object>>> mutations = List.of(
                c -> c.put("iss", "another-issuer"), c -> c.put("aud", List.of("another-api")),
                c -> c.remove("exp"), c -> c.put("sub", "not-a-number"), c -> c.remove("sid"),
                c -> c.put("sub", Long.toString(userId + 1)), c -> c.put("nbf", NOW.plusSeconds(60)));
        var key = new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Jwt original = jwt.decode(access);
        for (var mutation : mutations) {
            var claims = JwtClaimsSet.builder().claims(c -> { c.putAll(original.getClaims()); mutation.accept(c); }).build();
            String invalid = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                    .getTokenValue();
            me(invalid).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void concurrentRefreshCannotCreateTwoUsableSuccessors() throws Exception {
        var first = tokens.login(userId);
        var start = new CountDownLatch(1);
        Callable<String> refresh = () -> {
            start.await();
            try { tokens.refresh(first.refreshToken()); return "OK"; }
            catch (AuthTokenException exception) { return exception.code(); }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(refresh);
            var b = executor.submit(refresh);
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "REFRESH_TOKEN_REUSED");
        }
        assertThat(refreshTokens.count()).isEqualTo(2);
        me(first.access().accessToken()).andExpect(status().isUnauthorized());
        assertThat(sessions.findAll()).singleElement().satisfies(s -> assertThat(s.isRevoked()).isTrue());
    }

    @Test
    void refreshRacingWithLogoutCannotReviveTheLogin() throws Exception {
        var first = tokens.login(userId);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refreshed = executor.submit(() -> {
                start.await();
                try { return tokens.refresh(first.refreshToken()); }
                catch (AuthTokenException exception) { return null; }
            });
            var loggedOut = executor.submit(() -> { start.await(); tokens.logout(first.refreshToken()); return true; });
            start.countDown();
            var next = refreshed.get(10, TimeUnit.SECONDS);
            assertThat(loggedOut.get(10, TimeUnit.SECONDS)).isTrue();
            if (next != null) {
                me(next.access().accessToken()).andExpect(status().isUnauthorized());
                refresh(next.refreshToken(), null).andExpect(status().isUnauthorized());
            }
        }
        me(first.access().accessToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void signingKeyMustBeBase64AndAtLeast32Bytes() {
        for (String invalid : List.of("", "not base64!", Base64.getEncoder().encodeToString(new byte[16]))) {
            assertThatThrownBy(() -> new JwtTokens(invalid, sessions, clock))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("IPGO_JWT_SECRET");
        }
    }

    private ResultActions me(String access) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access));
    }

    private ResultActions refresh(String raw, String access) throws Exception {
        var request = post("/api/auth/refresh").with(csrf()).cookie(new Cookie("ipgo_refresh", raw));
        if (access != null) request.header("Authorization", "Bearer " + access);
        return mvc.perform(request);
    }
}
