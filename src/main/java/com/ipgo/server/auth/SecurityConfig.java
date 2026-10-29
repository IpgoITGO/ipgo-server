package com.ipgo.server.auth;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

import com.ipgo.server.common.ApiError;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    PasswordEncoder passwordEncoder() {
        String id = "pbkdf2-sha256-600k";
        var encoder = new Pbkdf2PasswordEncoder("", 16, 600_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder(id, Map.of(id, encoder));
    }

    @Bean
    AuthenticationManager authenticationManager(AuthService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    CookieCsrfTokenRepository csrfTokens(@Value("${ipgo.auth.cookie-secure:true}") boolean secure) {
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieName("IPGO-XSRF-TOKEN");
        repository.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(secure).sameSite("Lax").path("/"));
        return repository;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper,
            JwtTokens jwt, CookieCsrfTokenRepository csrfTokens) throws Exception {
        var bearer = new DefaultBearerTokenResolver();
        http.authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/actuator/health", "/api/auth/email-availability").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                                "/api/auth/email-verifications", "/api/auth/email-verifications/confirm").permitAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        // 로그인·로그아웃 컨트롤러에서 초기화한다. Bearer 요청마다 쿠키를 지우지 않는다.
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                        .requireCsrfProtectionMatcher(request -> cookieEndpoint(request)
                                && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)))
                .oauth2ResourceServer(resource -> resource
                        // 갱신·로그아웃은 만료된 Bearer가 있어도 쿠키로 처리하며 CSRF 면제 대상이 되지 않는다.
                        .bearerTokenResolver(request -> cookieEndpoint(request) ? null : bearer.resolve(request))
                        .jwt(config -> config.decoder(jwt))
                        .authenticationEntryPoint((request, response, exception) -> bearerError(jsonMapper, response, exception)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(jsonMapper, response,
                                401, "UNAUTHENTICATED", "로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, exception) -> writeError(jsonMapper, response,
                                403, "FORBIDDEN", "접근 권한 또는 CSRF 토큰을 확인해주세요.")));
        return http.build();
    }

    private static boolean cookieEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/auth/") && !path.equals("/api/auth/me");
    }

    private static void bearerError(JsonMapper mapper, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String code = exception instanceof OAuth2AuthenticationException ? "INVALID_ACCESS_TOKEN" : "UNAUTHENTICATED";
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException validation) {
                code = validation.getErrors().stream().map(error -> error.getErrorCode())
                        .filter(Set.of("ACCESS_TOKEN_EXPIRED", "INVALID_ACCESS_TOKEN", "SESSION_REVOKED")::contains)
                        .findFirst().orElse("INVALID_ACCESS_TOKEN");
                break;
            }
        }
        response.setHeader("WWW-Authenticate", "Bearer");
        writeError(mapper, response, 401, code,
                "ACCESS_TOKEN_EXPIRED".equals(code) ? "액세스 토큰이 만료되었습니다. 토큰을 갱신해주세요." : "다시 로그인해주세요.");
    }

    private static void writeError(JsonMapper mapper, HttpServletResponse response, int status,
            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(new ApiError(code, message)));
    }
}
