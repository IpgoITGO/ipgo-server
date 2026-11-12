package com.ipgo.server.service;

import com.ipgo.server.exception.AccountException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// #172 회원 탈퇴. 개인정보는 바로 지우고, 법으로 보관하는 결제 원장과 30일 보관 원본(V15)이 가리키는 회원 행은 남긴다.
// 수불부·상품·계산 결과는 로그인할 수 없는 이 계정에 남고, 실제 삭제는 보관 기간 뒤 정리 작업(후속)으로 한다.
@Service
public class AccountWithdrawalService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BillingService billing;
    private final Clock clock;

    public AccountWithdrawalService(PasswordEncoder passwordEncoder, JdbcTemplate jdbc, TransactionTemplate transactions,
            BillingService billing, Clock clock) {
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.billing = billing;
        this.clock = clock;
    }

    // 모든 단계를 한 transaction에서 회원 row를 잠근 채 처리한다. 결제 요청(BillingService)도 같은 회원 row를 잠그므로
    // 구독 재개 · 결제와 섞이지 않고, 중간에 실패하면 아무것도 바뀌지 않는다.
    public void withdraw(long userId, String password) {
        transactions.executeWithoutResult(status -> {
            List<String> hashes = jdbc.queryForList(
                    "SELECT password_hash FROM users WHERE id = ? AND withdrawn_at IS NULL FOR UPDATE", String.class, userId);
            if (hashes.isEmpty()) throw new UsernameNotFoundException("이메일 또는 비밀번호를 확인해주세요.");
            if (!passwordEncoder.matches(password, hashes.getFirst())) throw AccountException.wrongPassword();
            // 1) 다음 결제를 멈춘다. 결제가 진행 중이면 409 BILLING_IN_PROGRESS로 막고 탈퇴하지 않는다(재시도할 때도 매번 확인).
            billing.stopForWithdrawal(userId);
            // 2) 모든 로그인 세션을 폐기한다. 서버가 요청마다 세션을 확인하므로 이미 받은 access token도 바로 막힌다.
            jdbc.update("UPDATE auth_sessions SET revoked = TRUE WHERE user_id = ?", userId);
            // #190 알림 구독(브라우저 주소 · 키)과 알림 설정도 지워 탈퇴 뒤에는 알림이 가지 않게 한다.
            jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM notification_settings WHERE user_id = ?", userId);
            // 3) 개인정보를 지운다. 이메일은 회원 번호로 바꿔 같은 이메일로 바로 다시 가입할 수 있게 하고,
            //    비밀번호는 아무도 모르는 값으로 바꾼다. .invalid는 실제로 쓸 수 없는 예약 도메인이다.
            //    withdrawn_at이 생기면 늦게 도착한 결제 요청도 BillingService에서 ACCOUNT_WITHDRAWN으로 거절된다.
            jdbc.update("""
                    UPDATE users SET email = ?, password_hash = ?, company_name = NULL, manager_name = NULL,
                        business_reg_no = NULL, withdrawn_at = ? WHERE id = ?
                    """, "withdrawn-" + userId + "@withdrawn.invalid", passwordEncoder.encode(unguessable()),
                    java.sql.Timestamp.from(clock.instant()), userId);
        });
    }

    private static String unguessable() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
