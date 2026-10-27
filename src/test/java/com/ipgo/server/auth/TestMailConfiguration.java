package com.ipgo.server.auth;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@TestConfiguration(proxyBeanMethods = false)
class TestMailConfiguration {
    @Bean RecordingMailSender mailSender() { return new RecordingMailSender(); }

    static class RecordingMailSender extends JavaMailSenderImpl {
        final ConcurrentHashMap<String, String> codes = new ConcurrentHashMap<>();
        final AtomicInteger sent = new AtomicInteger();
        volatile boolean fail;

        @Override
        public void send(SimpleMailMessage message) {
            if (fail) throw new MailSendException("test-only SMTP failure");
            var matcher = java.util.regex.Pattern.compile("[0-9]{6}").matcher(message.getText());
            if (!matcher.find()) throw new AssertionError("Missing six-digit code");
            codes.put(message.getTo()[0], matcher.group());
            sent.incrementAndGet();
        }

        void reset() { codes.clear(); sent.set(0); fail = false; }
    }
}
