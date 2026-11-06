package com.ipgo.server.controller;

import com.ipgo.server.dto.request.PlannedQuantityRequest;
import com.ipgo.server.dto.response.PlannedQuantities;
import com.ipgo.server.service.PlannedQuantityService;
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

// #119 셀러가 정한 내 입고량
@RestController
public class PlannedQuantityController {
    private final PlannedQuantityService planned;

    public PlannedQuantityController(PlannedQuantityService planned) {
        this.planned = planned;
    }

    @GetMapping("/api/planned-quantities")
    public PlannedQuantities list(Principal principal) {
        return planned.list(userId(principal));
    }

    @PutMapping("/api/products/{productId}/planned-quantity")
    public PlannedQuantities.Item save(Principal principal, @PathVariable long productId,
            @Valid @RequestBody PlannedQuantityRequest request) {
        return planned.save(userId(principal), productId, request);
    }

    @DeleteMapping("/api/products/{productId}/planned-quantity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal principal, @PathVariable long productId) {
        planned.delete(userId(principal), productId);
    }

    private static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
