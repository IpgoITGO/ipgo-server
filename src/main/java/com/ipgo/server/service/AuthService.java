package com.ipgo.server.service;

import com.ipgo.server.dto.request.SignupRequest;
import com.ipgo.server.dto.response.AccountProfile;
import com.ipgo.server.entity.UserAccount;
import com.ipgo.server.repository.UserAccountRepository;
import com.ipgo.server.util.EmailNormalizer;

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
    private final EmailVerificationService verifications;

    public AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, EmailVerificationService verifications) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.verifications = verifications;
    }

    @Transactional
    public AccountProfile register(SignupRequest request) {
        String email = EmailNormalizer.normalize(request.email());
        if (users.existsByEmail(email)) throw EmailVerificationService.duplicate();
        verifications.consumeToken(email, request.verificationToken());
        try {
            UserAccount user = users.saveAndFlush(new UserAccount(email, passwordEncoder.encode(request.password()),
                    request.companyName(), request.managerName(), request.businessRegNo()));
            return profile(user);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        UserAccount user = users.findByEmail(EmailNormalizer.normalize(email))
                .orElseThrow(() -> new UsernameNotFoundException("이메일 또는 비밀번호를 확인해주세요."));
        return User.withUsername(user.getId().toString()).password(user.getPasswordHash()).roles("USER").build();
    }

    @Transactional(readOnly = true)
    public AccountProfile currentUser(long id) {
        return profile(users.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("이메일 또는 비밀번호를 확인해주세요.")));
    }

    private AccountProfile profile(UserAccount user) {
        return new AccountProfile(user.getId(), user.getEmail(), user.getManagerName(), user.getCompanyName());
    }

}
