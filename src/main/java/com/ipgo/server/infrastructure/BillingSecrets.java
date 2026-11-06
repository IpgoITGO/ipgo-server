package com.ipgo.server.infrastructure;

import com.ipgo.server.config.BillingProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class BillingSecrets {
    private final BillingProperties properties;
    private final SecureRandom random=new SecureRandom();
    public BillingSecrets(BillingProperties p) { properties=p; }
    public String encrypt(String value) {
        try {
            byte[] iv=new byte[12]; random.nextBytes(iv);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(properties.encryptionKey()),"AES"),new GCMParameterSpec(128,iv));
            c.updateAAD("ipgo-billing-test-v1".getBytes(StandardCharsets.UTF_8));
            return "v1."+Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) { throw new IllegalStateException("Billing encryption failed"); }
    }
    public String decrypt(String value) {
        try {
            String[] p=value.split("\\.");
            if(p.length!=3 || !"v1".equals(p[0])) throw new IllegalArgumentException();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(properties.encryptionKey()),"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(p[1])));
            c.updateAAD("ipgo-billing-test-v1".getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(Base64.getDecoder().decode(p[2])),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("Billing decryption failed"); }
    }
    public String fingerprint(String value) { return HexFormat.of().formatHex(hmac(Base64.getDecoder().decode(properties.lookupKey()),value.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8))); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static byte[] hmac(byte[] key, byte[] data) {
        try { Mac m=Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key,"HmacSHA256")); return m.doFinal(data); }
        catch(Exception e) { throw new IllegalStateException("Billing signature unavailable"); }
    }
}
