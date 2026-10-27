package com.ipgo.server.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, columnDefinition = "text")
    private String encryptedEmail;

    @Column(name = "email_lookup_hash", nullable = false, unique = true, length = 64)
    private String emailLookupHash;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    UserAccount(String encryptedEmail, String emailLookupHash, String passwordHash) {
        this.encryptedEmail = encryptedEmail;
        this.emailLookupHash = emailLookupHash;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }
}
