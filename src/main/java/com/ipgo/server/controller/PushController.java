package com.ipgo.server.controller;

import com.ipgo.server.dto.request.NotificationSettingsRequest;
import com.ipgo.server.dto.request.PushSubscriptionRequest;
import com.ipgo.server.dto.request.PushUnsubscribeRequest;
import com.ipgo.server.dto.response.NotificationSettings;
import com.ipgo.server.dto.response.PushPublicKey;
import com.ipgo.server.dto.response.PushSubscriptionSaved;
import com.ipgo.server.service.NotificationSettingsService;
import com.ipgo.server.service.PushSubscriptionService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// #190 Web Push 알림 1단계: 브라우저 등록 · 해제와 알림 설정. 실제 발송은 다음 단계에서 붙인다.
@RestController
public class PushController {
    private final PushSubscriptionService subscriptions;
    private final NotificationSettingsService settings;

    public PushController(PushSubscriptionService subscriptions, NotificationSettingsService settings) {
        this.subscriptions = subscriptions;
        this.settings = settings;
    }

    @GetMapping("/api/push/public-key")
    public PushPublicKey publicKey() {
        return subscriptions.publicKey();
    }

    // 처음 등록하면 201, 같은 브라우저를 다시 등록하면 200과 같은 id
    @PostMapping("/api/push/subscriptions")
    public ResponseEntity<PushSubscriptionSaved> subscribe(Principal principal,
            @Valid @RequestBody PushSubscriptionRequest request) {
        var saved = subscriptions.save(userId(principal), request);
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new PushSubscriptionSaved(saved.subscriptionId()));
    }

    @DeleteMapping("/api/push/subscriptions/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(Principal principal, @PathVariable long subscriptionId) {
        subscriptions.delete(userId(principal), subscriptionId);
    }

    // 로그아웃 직전에 부른다. id를 저장해 두지 않아도 브라우저 주소로 지울 수 있고, 없어도 204다.
    @DeleteMapping("/api/push/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribeThisBrowser(Principal principal, @Valid @RequestBody PushUnsubscribeRequest request) {
        subscriptions.deleteByEndpoint(userId(principal), request.endpoint());
    }

    @GetMapping("/api/notifications/settings")
    public NotificationSettings settings(Principal principal) {
        return settings.get(userId(principal));
    }

    @PatchMapping("/api/notifications/settings")
    public NotificationSettings updateSettings(Principal principal,
            @Valid @RequestBody NotificationSettingsRequest request) {
        return settings.update(userId(principal), request);
    }

    private static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
