package com.ipgo.server.service;

import com.ipgo.server.dto.response.FoundAccounts;
import com.ipgo.server.dto.response.ResetToken;
import com.ipgo.server.entity.EmailVerification;
import com.ipgo.server.exception.AccountRecoveryException;
import com.ipgo.server.infrastructure.mail.VerificationMailer;
import com.ipgo.server.repository.EmailVerificationRepository;
import com.ipgo.server.repository.UserAccountRepository;
import com.ipgo.server.util.EmailNormalizer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// #173 아이디 찾기 · 비밀번호 재설정.
// 코드 · 토큰은 회원가입 메일 인증 기록(email_verifications)을 그대로 쓴다. 가입 인증은 "가입 안 한 이메일"에만,
// 비밀번호 재설정은 "가입한 이메일"에만 동작해서 같은 줄을 두 흐름이 동시에 쓰지 않는다.
@Service
public class AccountRecoveryService {
    // 탈퇴한 계정(#172)은 이메일을 이 도메인으로 바꾼다. 실제로 메일을 받을 수 없어 찾기 · 재설정 대상이 아니다.
    static final String WITHDRAWN_DOMAIN = "@withdrawn.invalid";

    private final EmailVerificationRepository verifications;
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final VerificationMailer mailer;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AccountRecoveryService(EmailVerificationRepository verifications, UserAccountRepository users,
            PasswordEncoder encoder, VerificationMailer mailer, JdbcTemplate jdbc, Clock clock) {
        this.verifications = verifications;
        this.users = users;
        this.encoder = encoder;
        this.mailer = mailer;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    static final int MAX_FOUND = 5;

    // #185 회사명 + 담당자 이름이 둘 다 맞는 계정의 가린 이메일을 최근 가입 순으로 최대 5개 준다.
    // 띄어쓰기 · 영문 대소문자는 무시하고, 회사명은 (주) · 주식회사 · ㈜ 표기 차이도 무시한다. 탈퇴 계정은 빼요.
    @Transactional(readOnly = true)
    public FoundAccounts findId(String companyName, String managerName) {
        String company = companyKey(companyName);
        String manager = managerKey(managerName);
        if (company.isEmpty()) throw AccountRecoveryException.blankField("companyName");
        if (manager.isEmpty()) throw AccountRecoveryException.blankField("managerName");
        List<String> emails = jdbc.query("""
                SELECT email, company_name FROM users
                WHERE LOWER(REPLACE(manager_name, ' ', '')) = ? AND email NOT LIKE ? ORDER BY id DESC
                """, (rs, n) -> company.equals(companyKey(rs.getString(2))) ? rs.getString(1) : null,
                manager, "%" + WITHDRAWN_DOMAIN).stream().filter(java.util.Objects::nonNull).limit(MAX_FOUND).toList();
        if (emails.isEmpty()) throw AccountRecoveryException.accountNotFound();
        List<String> masked = emails.stream().map(AccountRecoveryService::mask).toList();
        return new FoundAccounts(masked, masked.getFirst());
    }

    // 예: "(주) Ipgo ITGO" · "주식회사 ipgoitgo" · "㈜IPGO ITGO" → "ipgoitgo"
    static String companyKey(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT).replace("주식회사", "").replace("(주)", "").replace("（주）", "")
                .replace("㈜", "").replace(" ", "").strip();
    }

    // 예: "홍 길동" → "홍길동". DB 쪽 비교(LOWER · REPLACE)와 같은 규칙이다.
    static String managerKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace(" ", "").strip();
    }

    // 가입 여부를 알리지 않으려고 없는 이메일 · 1분 안 재요청도 조용히 끝낸다(화면은 늘 "보냈어요").
    @Transactional
    public void requestCode(String rawEmail) {
        String email = EmailNormalizer.normalize(rawEmail);
        if (!registered(email)) return;
        verifications.ensureExists(email);
        EmailVerification state = verifications.findForUpdate(email).orElseThrow();
        Instant now = clock.instant();
        if (state.getLastSentAt() != null && now.isBefore(state.getLastSentAt().plusSeconds(60))) return;
        String code;
        do {
            code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        } while (state.getCodeHash() != null && encoder.matches(code, state.getCodeHash()));
        state.issueCode(encoder.encode(code), now);
        verifications.flush();
        mailer.sendPasswordReset(email, code);
    }

    // 틀림 · 만료 · 5번 실패 · 가입하지 않은 이메일은 모두 INVALID_CODE로 같게 답한다. 실패 횟수는 오류여도 저장한다.
    @Transactional(noRollbackFor = AccountRecoveryException.class)
    public ResetToken verify(String rawEmail, String code) {
        String email = EmailNormalizer.normalize(rawEmail);
        if (!registered(email)) throw AccountRecoveryException.invalidCode();
        EmailVerification state = verifications.findForUpdate(email).orElseThrow(AccountRecoveryException::invalidCode);
        if (state.getCodeHash() == null || state.getFailedAttempts() >= 5) throw AccountRecoveryException.invalidCode();
        Instant now = clock.instant();
        if (!state.getCodeExpiresAt().isAfter(now)) {
            state.invalidateCode();
            throw AccountRecoveryException.invalidCode();
        }
        if (!encoder.matches(code, state.getCodeHash())) {
            state.failCode();
            throw AccountRecoveryException.invalidCode();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        state.issueToken(EmailVerificationService.hash(token), now);
        return new ResetToken(token);
    }

    // 새 비밀번호를 저장하고 토큰을 한 번만 쓰게 지운다. 다른 기기의 로그인도 모두 끊는다.
    @Transactional
    public void reset(String token, String newPassword) {
        EmailVerification state = verifications.findByTokenHashForUpdate(EmailVerificationService.hash(token))
                .orElseThrow(AccountRecoveryException::invalidToken);
        if (state.getTokenExpiresAt() == null || !state.getTokenExpiresAt().isAfter(clock.instant())) {
            throw AccountRecoveryException.invalidToken();
        }
        var user = users.findByEmail(state.getEmail()).orElseThrow(AccountRecoveryException::invalidToken);
        state.consumeToken();
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", encoder.encode(newPassword), user.getId());
        jdbc.update("UPDATE auth_sessions SET revoked = TRUE WHERE user_id = ?", user.getId());
    }

    private boolean registered(String email) {
        return !email.endsWith(WITHDRAWN_DOMAIN) && users.existsByEmail(email);
    }

    // seller@a.com → se***@a.com, a@b.com → a***@b.com
    static String mask(String email) {
        int at = email.indexOf('@');
        String local = at < 0 ? email : email.substring(0, at);
        String domain = at < 0 ? "" : email.substring(at);
        return local.substring(0, Math.min(2, Math.max(1, local.length() - 1))) + "***" + domain;
    }
}
