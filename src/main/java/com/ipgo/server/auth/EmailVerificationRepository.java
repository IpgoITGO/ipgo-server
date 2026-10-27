package com.ipgo.server.auth;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, String> {
    @Modifying
    @Query(value = "INSERT INTO email_verifications (email, failed_attempts) VALUES (:email, 0) ON CONFLICT DO NOTHING", nativeQuery = true)
    void ensureExists(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EmailVerification v where v.email = :email")
    Optional<EmailVerification> findForUpdate(@Param("email") String email);
}
