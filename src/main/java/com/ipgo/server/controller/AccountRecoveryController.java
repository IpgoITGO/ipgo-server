package com.ipgo.server.controller;

import com.ipgo.server.dto.request.FindIdRequest;
import com.ipgo.server.dto.request.PasswordResetCodeRequest;
import com.ipgo.server.dto.request.PasswordResetRequest;
import com.ipgo.server.dto.request.PasswordResetVerifyRequest;
import com.ipgo.server.dto.response.FoundAccounts;
import com.ipgo.server.dto.response.ResetToken;
import com.ipgo.server.service.AccountRecoveryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// #173 로그인 화면의 아이디 찾기 · 비밀번호 재설정. 로그인 전에 쓰므로 인증 없이 열려 있고 CSRF는 다른 인증 POST와 같다.
@RestController
@RequestMapping("/api/auth")
public class AccountRecoveryController {
    private final AccountRecoveryService recovery;

    public AccountRecoveryController(AccountRecoveryService recovery) {
        this.recovery = recovery;
    }

    @PostMapping("/find-id")
    public FoundAccounts findId(@Valid @RequestBody FindIdRequest request) {
        return recovery.findId(request.companyName(), request.managerName());
    }

    @PostMapping("/password-reset/code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestCode(@Valid @RequestBody PasswordResetCodeRequest request) {
        recovery.requestCode(request.email());
    }

    @PostMapping("/password-reset/verify")
    public ResetToken verify(@Valid @RequestBody PasswordResetVerifyRequest request) {
        return recovery.verify(request.email(), request.code());
    }

    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody PasswordResetRequest request) {
        recovery.reset(request.resetToken(), request.newPassword());
    }
}
