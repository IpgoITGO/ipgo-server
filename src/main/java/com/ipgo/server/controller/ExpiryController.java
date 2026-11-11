package com.ipgo.server.controller;

import com.ipgo.server.dto.request.ExpirySettingsRequest;
import com.ipgo.server.dto.response.ExpiryList;
import com.ipgo.server.dto.response.ExpirySettings;
import com.ipgo.server.service.ExpiryService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// #168 유통기한 관리: 남은 재고의 LOT별 유통기한 목록과 임박 기준 설정
@RestController
public class ExpiryController {
    private final ExpiryService expiry;

    public ExpiryController(ExpiryService expiry) {
        this.expiry = expiry;
    }

    @GetMapping("/api/expiry")
    public ExpiryList list(Principal principal, @RequestParam(required = false) ExpiryList.Status status,
            @RequestParam(required = false) Long productId) {
        return expiry.list(userId(principal), status, productId);
    }

    @GetMapping("/api/settings/expiry")
    public ExpirySettings settings(Principal principal) {
        return expiry.settings(userId(principal));
    }

    @PutMapping("/api/settings/expiry")
    public ExpirySettings saveSettings(Principal principal, @Valid @RequestBody ExpirySettingsRequest request) {
        return expiry.saveSettings(userId(principal), request);
    }

    private static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
