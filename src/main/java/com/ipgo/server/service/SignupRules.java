package com.ipgo.server.service;

import com.ipgo.server.config.SignupProperties;
import com.ipgo.server.dto.request.SignupRequest;
import com.ipgo.server.exception.AccountException;
import com.ipgo.server.util.BusinessNumber;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// #206 가입 동의 · 사업자등록번호 규칙.
// 전환 기간(strict=false)에는 지금 가입 화면이 깨지지 않게 동의 · 사업자등록번호가 없어도 통과시키고,
// 보낸 값만 검사한다. strict=true가 되면 둘 다 필수다.
@Component
@EnableConfigurationProperties(SignupProperties.class)
public class SignupRules {
    private final SignupProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SignupRules(SignupProperties properties, JdbcTemplate jdbc, Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // 가입 전에 검사하고, 저장할 사업자등록번호(숫자 10자리 또는 null)를 돌려준다.
    public String check(SignupRequest request) {
        var agreements = request.agreements();
        if (agreements != null || properties.strict()) {
            if (agreements == null || !Boolean.TRUE.equals(agreements.terms()) || !Boolean.TRUE.equals(agreements.privacy())) {
                throw AccountException.invalidField("agreements", "필수 약관에 모두 동의해 주세요.");
            }
            if (!properties.agreementVersion().equals(agreements.version())) {
                throw AccountException.invalidField("agreements", "약관이 바뀌었어요. 새로고침한 뒤 다시 동의해 주세요.");
            }
        }
        if (request.businessRegNo() == null) {
            if (properties.strict()) throw AccountException.invalidField("businessRegNo", "사업자등록번호를 입력해 주세요.");
            return null;
        }
        String digits = BusinessNumber.normalize(request.businessRegNo());
        if (digits == null || (properties.strict() && !BusinessNumber.validChecksum(digits))) {
            throw AccountException.invalidField("businessRegNo", "사업자등록번호를 확인해 주세요.");
        }
        return digits;
    }

    // 동의를 보낸 경우에만 버전과 시각을 남긴다(가입과 같은 transaction).
    public void record(long userId, SignupRequest request) {
        if (request.agreements() == null) return;
        jdbc.update("UPDATE users SET agreement_version = ?, agreed_at = ? WHERE id = ?",
                request.agreements().version(), Timestamp.from(clock.instant()), userId);
    }
}
