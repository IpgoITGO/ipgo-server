package com.ipgo.server.auth;

import java.security.Principal;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
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
