package com.ipgo.server.controller;

import com.ipgo.server.dto.request.ReceiptCorrectionRequest;
import com.ipgo.server.dto.response.ReceiptCorrection;
import com.ipgo.server.service.ReceiptCorrectionService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/receipt-corrections")
public class ReceiptCorrectionController {
    private final ReceiptCorrectionService corrections;
    public ReceiptCorrectionController(ReceiptCorrectionService corrections) { this.corrections=corrections; }
    @GetMapping
    public ReceiptCorrection.Page list(Principal user,@RequestParam(defaultValue="OPEN") ReceiptCorrection.State state,
            @RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="20") int size) {
        return corrections.list(Long.parseLong(user.getName()),state,cursor,size);
    }
    @GetMapping("/{reviewId}")
    public ReceiptCorrection.Detail detail(Principal user,@PathVariable long reviewId) {
        return corrections.detail(Long.parseLong(user.getName()),reviewId);
    }
    @PostMapping("/{reviewId}/preview")
    public ReceiptCorrection.Preview preview(Principal user,@PathVariable long reviewId,@Valid @RequestBody ReceiptCorrectionRequest.Preview request) {
        return corrections.preview(Long.parseLong(user.getName()),reviewId,request);
    }
    @PostMapping("/{reviewId}/decisions")
    public ReceiptCorrection.Saved decide(Principal user,@PathVariable long reviewId,@Valid @RequestBody ReceiptCorrectionRequest.Decide request) {
        return corrections.decide(Long.parseLong(user.getName()),reviewId,request);
    }
}
