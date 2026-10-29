package com.ipgo.server.auth;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final AuthenticationManager authentication;
    private final TokenService tokens;
    private final CookieCsrfTokenRepository csrfTokens;
    private final Clock clock;
    private final boolean secure;

    public AuthController(AuthService authService, AuthenticationManager authentication, TokenService tokens,
            CookieCsrfTokenRepository csrfTokens, Clock clock, @Value("${ipgo.auth.cookie-secure:true}") boolean secure) {
        this.authService = authService;
        this.authentication = authentication;
        this.tokens = tokens;
        this.csrfTokens = csrfTokens;
        this.clock = clock;
        this.secure = secure;
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return token;
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public AccessTokenResponse login(@RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "") String password,
            HttpServletRequest request, HttpServletResponse response) {
        long userId;
        try {
            userId = Long.parseLong(authentication.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, password)).getName());
        } catch (AuthenticationException exception) {
            throw new AuthTokenException("INVALID_CREDENTIALS", "이메일 또는 비밀번호를 확인해주세요.");
        }
        var issued = tokens.login(userId);
        csrfTokens.saveToken(null, request, response);
        return respond(issued, response);
    }

    @PostMapping("/refresh")
    public AccessTokenResponse refresh(@CookieValue(name = "ipgo_refresh", required = false) String refreshToken,
            HttpServletResponse response) {
        try {
            return respond(tokens.refresh(refreshToken), response);
        } catch (AuthTokenException exception) {
            refreshCookie(response, "", 0);
            throw exception;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = "ipgo_refresh", required = false) String refreshToken,
            HttpServletRequest request, HttpServletResponse response) {
        tokens.logout(refreshToken);
        refreshCookie(response, "", 0);
        csrfTokens.saveToken(null, request, response);
    }

    private AccessTokenResponse respond(TokenService.IssuedTokens issued, HttpServletResponse response) {
        refreshCookie(response, issued.refreshToken(), Math.max(0, Duration.between(clock.instant(), issued.expiresAt()).getSeconds()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return issued.access();
    }

    private void refreshCookie(HttpServletResponse response, String value, long maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("ipgo_refresh", value)
                .httpOnly(true).secure(secure).sameSite("Lax").path("/api/auth").maxAge(maxAge).build().toString());
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountProfile signup(@Valid @RequestBody SignupRequest request) {
        return authService.register(request);
    }

    @GetMapping("/me")
    public AccountProfile me(Principal principal) {
        return authService.currentUser(Long.parseLong(principal.getName()));
    }

}
