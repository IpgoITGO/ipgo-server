package com.ipgo.server.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PersonalDataCrypto {
    // shortcut: 단일 키 세트를 사용하므로, 키 교체 시 재암호화와 검색 해시 갱신 절차가 필요하다.
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec lookupKey;
    private final SecureRandom random = new SecureRandom();

    public PersonalDataCrypto(@Value("${IPGO_PII_AES_KEY:}") String encryptionKey,
            @Value("${IPGO_PII_LOOKUP_KEY:}") String lookupKey) {
        byte[] aesBytes = decodeKey(encryptionKey, "IPGO_PII_AES_KEY");
        byte[] lookupBytes = decodeKey(lookupKey, "IPGO_PII_LOOKUP_KEY");
        if (MessageDigest.isEqual(aesBytes, lookupBytes)) {
            throw new IllegalArgumentException("개인정보 암호화 키와 검색 키는 서로 달라야 합니다.");
        }
        this.encryptionKey = new SecretKeySpec(aesBytes, "AES");
        this.lookupKey = new SecretKeySpec(lookupBytes, "HmacSHA256");
    }

    public String emailLookup(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(lookupKey);
            return HexFormat.of().formatHex(mac.doFinal(normalizeEmail(email).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("개인정보 검색 값을 생성할 수 없습니다.");
        }
    }

    public String encryptEmail(String email) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, emailLookup(email));
            byte[] encrypted = cipher.doFinal(normalizeEmail(email).getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("개인정보를 암호화할 수 없습니다.");
        }
    }

    public String decryptEmail(String encryptedEmail, String emailLookup) {
        try {
            if (!encryptedEmail.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] payload = Base64.getDecoder().decode(encryptedEmail.substring(3));
            if (payload.length < 29) throw new IllegalArgumentException();
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, emailLookup).doFinal(encrypted),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            // 손상된 암호문이나 키를 오류 응답·로그에 포함하지 않는다.
            throw new IllegalStateException("개인정보 복호화 또는 무결성 확인에 실패했습니다.");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String emailLookup) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, encryptionKey, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("users.email:v1:" + emailLookup).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static byte[] decodeKey(String value, String name) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length == 32) return bytes;
        } catch (IllegalArgumentException ignored) {
        }
        throw new IllegalArgumentException(name + "에 Base64로 인코딩한 32바이트 키가 필요합니다.");
    }
}
