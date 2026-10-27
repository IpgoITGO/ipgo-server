package com.ipgo.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {
    private final EmailVerificationRepository verifications;
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final VerificationMailer mailer;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(EmailVerificationRepository verifications, UserAccountRepository users,
            PasswordEncoder encoder, VerificationMailer mailer, Clock clock) {
        this.verifications = verifications;
        this.users = users;
        this.encoder = encoder;
        this.mailer = mailer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean available(String email) {
        return !users.existsByEmail(AuthService.normalizeEmail(email));
    }

    @Transactional
    public void requestCode(String rawEmail) {
        String email = AuthService.normalizeEmail(rawEmail);
        if (users.existsByEmail(email)) throw duplicate();
        verifications.ensureExists(email);
        EmailVerification state = verifications.findForUpdate(email).orElseThrow();
        if (users.existsByEmail(email)) throw duplicate();
        Instant now = clock.instant();
        if (state.getLastSentAt() != null && now.isBefore(state.getLastSentAt().plusSeconds(60))) {
            throw new EmailVerificationException(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_VERIFICATION_RATE_LIMITED",
                    "인증 메일은 1분에 한 번 요청할 수 있습니다.");
        }
        String code;
        do {
            code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        } while (state.getCodeHash() != null && encoder.matches(code, state.getCodeHash()));
        state.issueCode(encoder.encode(code), now);
        verifications.flush();
        // 동일 이메일 잠금을 유지한 채 발송한다. SMTP 시간 제한으로 잠금 대기를 제한한다.
        mailer.send(email, code);
    }

    // 실패 횟수·만료 상태는 오류를 응답하더라도 commit해야 한다.
    @Transactional(noRollbackFor = EmailVerificationException.class)
    public String confirm(String rawEmail, String code) {
        String email = AuthService.normalizeEmail(rawEmail);
        EmailVerification state = verifications.findForUpdate(email).orElseThrow(EmailVerificationService::invalidCode);
        if (state.getCodeHash() == null || state.getFailedAttempts() >= 5) throw invalidCode();
        Instant now = clock.instant();
        if (!state.getCodeExpiresAt().isAfter(now)) {
            state.invalidateCode();
            throw new EmailVerificationException(HttpStatus.BAD_REQUEST, "EMAIL_CODE_EXPIRED", "인증 코드가 만료되었습니다.");
        }
        if (!encoder.matches(code, state.getCodeHash())) {
            state.failCode();
            throw invalidCode();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        state.issueToken(hash(token), now);
        return token;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeToken(String email, String token) {
        EmailVerification state = verifications.findForUpdate(email).orElseThrow(EmailVerificationService::invalidToken);
        if (token == null || state.getTokenHash() == null || !state.getTokenExpiresAt().isAfter(clock.instant())
                || !MessageDigest.isEqual(state.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                        hash(token).getBytes(StandardCharsets.US_ASCII))) throw invalidToken();
        state.consumeToken();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static EmailVerificationException duplicate() {
        return new EmailVerificationException(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "이미 가입된 이메일입니다.");
    }

    private static EmailVerificationException invalidCode() {
        return new EmailVerificationException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_CODE",
                "인증 코드가 올바르지 않거나 사용할 수 없습니다. 다시 인증해주세요.");
    }

    private static EmailVerificationException invalidToken() {
        return new EmailVerificationException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_TOKEN",
                "이메일 인증을 다시 진행해주세요.");
    }
}
