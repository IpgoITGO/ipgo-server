package com.ipgo.server.infrastructure.mail;

import com.ipgo.server.exception.EmailVerificationException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class VerificationMailer {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;

    public VerificationMailer(ObjectProvider<JavaMailSender> senders,
            @Value("${ipgo.mail.from:}") String from) {
        this.senders = senders;
        this.from = from;
    }

    public void send(String email, String code) {
        deliver(email, "Ipgo 회원가입 이메일 인증",
                "인증 코드: " + code + "\n10분 이내에 입력해주세요. 요청하지 않았다면 이 메일을 무시해주세요.");
    }

    // #173 비밀번호 재설정 코드
    public void sendPasswordReset(String email, String code) {
        deliver(email, "Ipgo 비밀번호 재설정",
                "비밀번호 재설정 코드: " + code + "\n10분 이내에 입력해주세요. 요청하지 않았다면 이 메일을 무시해주세요. 비밀번호는 바뀌지 않아요.");
    }

    private void deliver(String email, String subject, String text) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) throw unavailable();
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(text);
        try {
            sender.send(message);
        } catch (MailException exception) {
            // SMTP 예외에는 수신자와 인증 정보가 포함될 수 있어 그대로 기록하지 않는다.
            throw unavailable();
        }
    }

    private static EmailVerificationException unavailable() {
        return new EmailVerificationException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                "인증 메일을 발송하지 못했습니다. 잠시 후 다시 시도해주세요.");
    }
}
