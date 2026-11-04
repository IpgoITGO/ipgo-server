package com.ipgo.server.controller;

import com.ipgo.server.dto.request.DefaultSettingsRequest;
import com.ipgo.server.dto.request.ProductSettingsRequest;
import com.ipgo.server.dto.response.DefaultSettings;
import com.ipgo.server.dto.response.SettingsSaved;
import com.ipgo.server.service.ReplenishmentService;

import java.security.Principal;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReplenishmentController {
    private final ReplenishmentService replenishment;

    public ReplenishmentController(ReplenishmentService replenishment) {
        this.replenishment = replenishment;
    }

    // S1 전체 입고 조건 기본값
    @GetMapping("/api/replenishment-settings")
    public DefaultSettings defaults(Principal principal) {
        return replenishment.defaults(userId(principal));
    }

    // S2 전체 기본값 저장 (확인하고 계산하기)
    @PutMapping("/api/replenishment-settings")
    public SettingsSaved saveDefaults(Principal principal, @Valid @RequestBody DefaultSettingsRequest request) {
        return replenishment.saveDefaults(userId(principal), request);
    }

    // S3 상품별 입고 조건 저장
    @PutMapping("/api/products/{productId}/replenishment-settings")
    public SettingsSaved saveForProduct(Principal principal, @PathVariable long productId,
            @Valid @RequestBody ProductSettingsRequest request) {
        return replenishment.saveForProduct(userId(principal), productId, request);
    }

    private static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
