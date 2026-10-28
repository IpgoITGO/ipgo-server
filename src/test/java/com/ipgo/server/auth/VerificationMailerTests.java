package com.ipgo.server.auth;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.*;

class VerificationMailerTests {
    @Test
    void sendsCodeThroughRealSmtpToLocalTestServer() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var delivery = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII);
                    output.print("220 localhost test SMTP\r\n"); output.flush();
                    var content = new StringBuilder();
                    String line;
                    boolean data = false;
                    while ((line = input.readLine()) != null) {
                        if (data && !line.equals(".")) { content.append(line).append("\r\n"); continue; }
                        if (data) { data = false; output.print("250 accepted\r\n"); }
                        else if (line.equals("DATA")) { data = true; output.print("354 send data\r\n"); }
                        else if (line.equals("QUIT")) { output.print("221 bye\r\n"); output.flush(); break; }
                        else output.print("250 OK\r\n");
                        output.flush();
                    }
                    return content.toString();
                }
            });
            var sender = new JavaMailSenderImpl();
            sender.setHost(server.getInetAddress().getHostAddress());
            sender.setPort(server.getLocalPort());
            sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "3000");
            sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "3000");
            sender.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "3000");
            var factory = new DefaultListableBeanFactory();
            factory.registerSingleton("sender", sender);
            new VerificationMailer(factory.getBeanProvider(JavaMailSender.class), "noreply@example.test")
                    .send("seller@example.test", "012345");
            var message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(
                    delivery.get(5, TimeUnit.SECONDS).getBytes(StandardCharsets.US_ASCII)));
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo("seller@example.test");
            assertThat(message.getSubject()).isEqualTo("Ipgo 회원가입 이메일 인증");
            assertThat(message.getContent().toString()).contains("012345", "10분");
        }
    }

    @Test
    void absentSmtpConfigurationReturnsExplicitServiceUnavailable() {
        var mailer = new VerificationMailer(new DefaultListableBeanFactory().getBeanProvider(JavaMailSender.class), "");
        assertThatThrownBy(() -> mailer.send("seller@example.test", "012345"))
                .isInstanceOf(EmailVerificationException.class)
                .satisfies(error -> assertThat(((EmailVerificationException) error).code()).isEqualTo("EMAIL_DELIVERY_UNAVAILABLE"));
    }
}
