package com.ipgo.server.auth;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService implements UserDetailsService {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AccountProfile register(String email, String password) {
        try {
            UserAccount user = users.saveAndFlush(new UserAccount(normalizeEmail(email),
                    passwordEncoder.encode(password)));
            return profile(user);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        UserAccount user = users.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("이메일 또는 비밀번호를 확인해주세요."));
        return User.withUsername(user.getId().toString()).password(user.getPasswordHash()).roles("USER").build();
    }

    @Transactional(readOnly = true)
    public AccountProfile currentUser(long id) {
        return profile(users.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("이메일 또는 비밀번호를 확인해주세요.")));
    }

    private AccountProfile profile(UserAccount user) {
        return new AccountProfile(user.getId(), user.getEmail(), user.getCreatedAt());
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
