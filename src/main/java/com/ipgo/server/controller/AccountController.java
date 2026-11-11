package com.ipgo.server.controller;

import com.ipgo.server.dto.request.WithdrawRequest;
import com.ipgo.server.service.AccountWithdrawalService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// #172 내 계정: 회원 탈퇴
@RestController
public class AccountController {
    private final AccountWithdrawalService withdrawals;
    private final boolean secureCookie;

    public AccountController(AccountWithdrawalService withdrawals,
            @Value("${ipgo.auth.cookie-secure:true}") boolean secureCookie) {
        this.withdrawals = withdrawals;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/api/account/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(Principal principal, @Valid @RequestBody WithdrawRequest request, HttpServletResponse response) {
        withdrawals.withdraw(Long.parseLong(principal.getName()), request.password());
        // 로그아웃처럼 브라우저의 refresh 쿠키도 지운다.
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("ipgo_refresh", "")
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/api/auth").maxAge(0).build().toString());
    }
}
