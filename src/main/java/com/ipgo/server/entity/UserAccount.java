package com.ipgo.server.entity;

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

    @Column(name = "email", nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "company_name", length = 50)
    private String companyName;

    @Column(name = "manager_name", length = 50)
    private String managerName;

    @Column(name = "business_reg_no", length = 10)
    private String businessRegNo;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UserAccount(String email, String passwordHash, String companyName, String managerName, String businessRegNo) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.companyName = companyName;
        this.managerName = managerName;
        this.businessRegNo = businessRegNo;
        this.createdAt = Instant.now();
    }
}
