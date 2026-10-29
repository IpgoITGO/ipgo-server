package com.ipgo.server.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthCookieTests {
    @Test
    void httpsCookiesKeepSecureHttpOnlyAndMatchingPathsWhenIssuedAndDeleted() {
        Instant now = Instant.parse("2026-10-29T01:00:00Z");
        var tokens = mock(TokenService.class);
        when(tokens.refresh("test-refresh")).thenReturn(new TokenService.IssuedTokens(
                new AccessTokenResponse("test-access", "Bearer", 900), "next-refresh", now.plusSeconds(604800)));
        var csrf = new SecurityConfig().csrfTokens(true);
        var controller = new AuthController(null, null, tokens, csrf, Clock.fixed(now, ZoneOffset.UTC), true);
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        var issued = new MockHttpServletResponse();
        controller.refresh("test-refresh", issued);
        csrf.saveToken(csrf.generateToken(request), request, issued);

        var deleted = new MockHttpServletResponse();
        controller.logout("next-refresh", request, deleted);
        for (var response : new MockHttpServletResponse[] { issued, deleted }) {
            for (String name : new String[] { "ipgo_refresh", "IPGO-XSRF-TOKEN" }) {
                var cookie = response.getCookie(name);
                assertThat(cookie).isNotNull();
                assertThat(cookie.getSecure()).isTrue();
                assertThat(cookie.isHttpOnly()).isTrue();
                assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
                assertThat(cookie.getPath()).isEqualTo(name.equals("ipgo_refresh") ? "/api/auth" : "/");
                assertThat(cookie.getDomain()).isNull();
                if (response == deleted) assertThat(cookie.getMaxAge()).isZero();
            }
        }
    }
}
