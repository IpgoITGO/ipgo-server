package com.ipgo.server.controller;

import com.ipgo.server.dto.request.PlannedReceiptRequest;
import com.ipgo.server.dto.request.PlannedReceiptUpdateRequest;
import com.ipgo.server.dto.response.PlannedReceiptCreated;
import com.ipgo.server.dto.response.PlannedReceiptItem;
import com.ipgo.server.service.PlannedReceiptService;

import java.security.Principal;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/planned-receipts")
public class PlannedReceiptController {
    private final PlannedReceiptService receipts;

    public PlannedReceiptController(PlannedReceiptService receipts) {
        this.receipts = receipts;
    }

    // R1 기존 발주 목록 · 도착 상태
    @GetMapping
    public PlannedReceiptItem.Page list(Principal principal) {
        return receipts.list(userId(principal));
    }

    // R2 기존 발주 등록
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlannedReceiptCreated create(Principal principal, @Valid @RequestBody PlannedReceiptRequest request) {
        return receipts.create(userId(principal), request);
    }

    // R3 수량 · 도착 예정일 수정
    @PatchMapping("/{receiptId}")
    public PlannedReceiptItem update(Principal principal, @PathVariable long receiptId,
            @Valid @RequestBody PlannedReceiptUpdateRequest request) {
        return receipts.update(userId(principal), receiptId, request);
    }

    // R3 취소. 행은 CANCELLED로 남기고 목록에서는 뺀다.
    @DeleteMapping("/{receiptId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(Principal principal, @PathVariable long receiptId) {
        receipts.cancel(userId(principal), receiptId);
    }

    private static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
