package com.ipgo.server.controller;

import com.ipgo.server.service.NormalPaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/billing/payments")
public class NormalPaymentController {
    private final NormalPaymentService service;
    public NormalPaymentController(NormalPaymentService s) { service=s; }
    public record Create(@NotBlank @Pattern(regexp="seller|business") String planId,@NotBlank String priceVersion,@NotBlank String consentVersion) { }
    public record Confirm(@NotBlank @Size(max=200) String paymentKey,@Positive long amount) {
        @Override public String toString() { return "PaymentConfirm[redacted]"; }
    }
    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @PostMapping public ResponseEntity<NormalPaymentService.Checkout> create(Principal p,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create r) {
        return ok(service.create(Long.parseLong(p.getName()),key,r.planId(),r.priceVersion(),r.consentVersion()));
    }
    @PostMapping("/{id}/confirm") public ResponseEntity<NormalPaymentService.Result> confirm(Principal p,@PathVariable String id,@Valid @RequestBody Confirm r) {
        return ok(service.confirm(Long.parseLong(p.getName()),id,r.paymentKey(),r.amount()));
    }
    @GetMapping("/{id}") public ResponseEntity<NormalPaymentService.Result> get(Principal p,@PathVariable String id) { return ok(service.get(Long.parseLong(p.getName()),id)); }
    @GetMapping public ResponseEntity<List<NormalPaymentService.Result>> pending(Principal p) { return ok(service.pending(Long.parseLong(p.getName()))); }
}
