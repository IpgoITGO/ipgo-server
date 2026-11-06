package com.ipgo.server.controller;

import com.ipgo.server.config.BillingProperties;
import com.ipgo.server.exception.BillingException;
import com.ipgo.server.infrastructure.BillingSecrets;
import com.ipgo.server.service.BillingService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class BillingWebhookController {
    private final BillingProperties properties;
    private final BillingService billing;
    private final JsonMapper mapper;
    public BillingWebhookController(BillingProperties p,BillingService b,JsonMapper m) { properties=p; billing=b; mapper=m; }
    @PostMapping("/api/billing/webhooks/toss")
    public ResponseEntity<Void> webhook(@RequestHeader(value="tosspayments-webhook-transmission-time",required=false) String time,
            @RequestHeader(value="tosspayments-webhook-signature",required=false) String signature,jakarta.servlet.http.HttpServletRequest request) {
        billing.requireEnabled();
        if(time==null || time.length()>100 || signature==null || signature.length()>4096)
            throw BillingException.forbidden();
        if(request.getContentLengthLong()>65536) throw new BillingException(413,"WEBHOOK_TOO_LARGE","웹훅 크기 제한을 초과했습니다.");
        byte[] body;
        try { body=request.getInputStream().readNBytes(65537); }
        catch(java.io.IOException e) { throw BillingException.invalid(); }
        if(body.length>65536) throw new BillingException(413,"WEBHOOK_TOO_LARGE","웹훅 크기 제한을 초과했습니다.");
        byte[] suffix=(":"+time).getBytes(StandardCharsets.UTF_8);
        byte[] signed=java.util.Arrays.copyOf(body,body.length+suffix.length);
        System.arraycopy(suffix,0,signed,body.length,suffix.length);
        byte[] expected=BillingSecrets.hmac(properties.securityKey().getBytes(StandardCharsets.UTF_8),signed);
        boolean verified=false;
        for(String token:signature.split(",")) {
            String t=token.trim(); if(!t.startsWith("v1:")) continue;
            try { verified|=MessageDigest.isEqual(expected,Base64.getDecoder().decode(t.substring(3))); }
            catch(IllegalArgumentException ignored) { /* 다른 키의 서명 또는 잘못된 토큰 */ }
        }
        if(!verified) throw BillingException.forbidden();
        JsonNode event;
        try { event=mapper.readTree(body); } catch(Exception e) { throw BillingException.invalid(); }
        String type=event.path("eventType").asText("");
        if("BILLING_DELETED".equals(type)) {
            String key=event.path("data").path("billingKey").asText("");
            if(key.isBlank() || key.length()>300) throw BillingException.invalid();
            // 이 이벤트는 키를 비활성화하는 단조 전이이므로 재전송/역순에 안전하다.
            billing.deletedWebhook(key,BillingSecrets.hash("BILLING_DELETED:"+key.toLowerCase(java.util.Locale.ROOT)));
        }
        // 빌링 승인은 성공 webhook이 없다. 다른 이벤트로 원장을 임의 변경하지 않는다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
}
