package com.ipgo.server.controller;

import com.ipgo.server.dto.request.ProductBoxRequest;
import com.ipgo.server.dto.request.StorageCostRequest;
import com.ipgo.server.dto.response.StorageCost;
import com.ipgo.server.service.StorageCostService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// #111 입고 조건 화면의 보관 비용 설정
@RestController
public class StorageCostController {
    private final StorageCostService storage;

    public StorageCostController(StorageCostService storage) {
        this.storage = storage;
    }

    @GetMapping("/api/settings/storage-cost")
    public StorageCost get(Principal principal) {
        return storage.get(userId(principal));
    }

    @PutMapping("/api/settings/storage-cost")
    public StorageCost saveRate(Principal principal, @Valid @RequestBody StorageCostRequest request) {
        return storage.saveRate(userId(principal), request);
    }

    @PutMapping("/api/products/{productId}/box")
    public StorageCost.Box saveBox(Principal principal, @PathVariable long productId,
            @Valid @RequestBody ProductBoxRequest request) {
        return storage.saveBox(userId(principal), productId, request);
    }

    @DeleteMapping("/api/products/{productId}/box")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBox(Principal principal, @PathVariable long productId) {
        storage.deleteBox(userId(principal), productId);
    }

    private static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
