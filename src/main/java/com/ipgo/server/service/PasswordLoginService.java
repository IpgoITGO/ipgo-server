package com.ipgo.server.service;

import com.ipgo.server.util.EmailNormalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 이메일 · 비밀번호 로그인. 비밀번호 확인과 세션 만들기를 회원 row를 잠근 한 transaction에서 한다.
// 비밀번호 바꾸기(#192) · 재설정(#173) · 탈퇴(#172)도 같은 row를 잠그므로, 그 사이에 끼어든 로그인이
// 옛 비밀번호로 확인을 통과한 뒤 폐기 대상에서 빠진 새 세션을 만들 수 없다.
// - 로그인이 먼저 잠그면: 세션을 만들고 commit한 뒤 비밀번호 바꾸기가 그 세션까지 폐기한다.
// - 비밀번호 바꾸기가 먼저 잠그면: 로그인은 기다렸다가 바뀐 비밀번호로 다시 확인해 옛 비밀번호면 실패한다.
@Service
public class PasswordLoginService {
    private final AuthenticationManager authentication;
    private final TokenService tokens;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PasswordLoginService(AuthenticationManager authentication, TokenService tokens, JdbcTemplate jdbc,
            TransactionTemplate transactions) {
        this.authentication = authentication;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    // 비밀번호가 틀리면 AuthenticationException을 그대로 던진다(세션은 만들지 않는다).
    public TokenService.IssuedTokens login(String email, String password) {
        return transactions.execute(status -> {
            // 없는 이메일이면 잠글 row가 없고, 아래 확인에서 실패한다.
            jdbc.queryForList("SELECT id FROM users WHERE email = ? FOR UPDATE", Long.class, EmailNormalizer.normalize(email));
            long userId = Long.parseLong(authentication.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, password)).getName());
            return tokens.login(userId);
        });
    }
}
