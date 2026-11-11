package com.ipgo.server.service;

import com.ipgo.server.config.BillingProperties;
import com.ipgo.server.entity.UserAccount;
import com.ipgo.server.exception.AccountException;
import com.ipgo.server.repository.UserAccountRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
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

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BillingService billing;
    private final BillingProperties billingProperties;
    private final Clock clock;

    public AccountWithdrawalService(UserAccountRepository users, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
            TransactionTemplate transactions, BillingService billing, BillingProperties billingProperties, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.billing = billing;
        this.billingProperties = billingProperties;
        this.clock = clock;
    }

    public void withdraw(long userId, String password) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("이메일 또는 비밀번호를 확인해주세요."));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) throw AccountException.wrongPassword();

        // 1) 다음 결제를 멈춘다. 결제가 진행 중이면 BillingService가 409로 막고, 탈퇴도 하지 않는다.
        //    같은 키라 다시 시도해도 해지는 한 번만 기록된다.
        if (billingProperties.enabled()) {
            billing.cancel(userId, "withdraw-" + userId);
        }

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
            // 결제 기능이 꺼져 있어도 예전 구독이 다시 청구되지 않게 막는다.
            jdbc.update("UPDATE billing_subscriptions SET cancel_at_period_end = TRUE, next_charge_at = NULL WHERE user_id = ?",
                    userId);
            // 2) 모든 로그인 세션을 폐기한다. 서버가 요청마다 세션을 확인하므로 이미 받은 access token도 바로 막힌다.
            jdbc.update("UPDATE auth_sessions SET revoked = TRUE WHERE user_id = ?", userId);
            // 3) 개인정보를 지운다. 이메일은 회원 번호로 바꿔 같은 이메일로 바로 다시 가입할 수 있게 하고,
            //    비밀번호는 아무도 모르는 값으로 바꾼다. .invalid는 실제로 쓸 수 없는 예약 도메인이다.
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
