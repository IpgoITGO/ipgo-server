package com.ipgo.server.controller;

import com.ipgo.server.dto.request.BillingRequests;
import com.ipgo.server.dto.response.BillingViews;
import com.ipgo.server.service.BillingService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final BillingService billing;
    private final com.ipgo.server.service.BillingRefundService refunds;
    private final com.ipgo.server.service.NormalPaymentService normal;
    public BillingController(BillingService b,com.ipgo.server.service.BillingRefundService refunds,com.ipgo.server.service.NormalPaymentService normal) { billing=b; this.refunds=refunds; this.normal=normal; }
    private long user(Principal p) { return Long.parseLong(p.getName()); }
    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @GetMapping("/plans") public ResponseEntity<List<BillingViews.Plan>> plans() { return ok(billing.plans()); }
    @GetMapping("/subscription") public ResponseEntity<BillingViews.Subscription> subscription(Principal p) { return ok(billing.subscription(user(p))); }
    @GetMapping("/payment-methods") public ResponseEntity<List<BillingViews.Method>> methods(Principal p) { return ok(billing.methods(user(p))); }
    @GetMapping("/invoices") public ResponseEntity<BillingViews.InvoicePage> invoices(Principal p,@RequestParam(required=false) String cursor) { return ok(billing.invoices(user(p),cursor)); }
    @PostMapping("/registration-sessions") public ResponseEntity<BillingViews.Registration> register(Principal p,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody BillingRequests.Registration r) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(billing.register(user(p),key,r));
    }
    @PostMapping("/registration-sessions/{id}/complete") public ResponseEntity<BillingViews.Session> complete(Principal p,@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody BillingRequests.Complete r) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(billing.complete(user(p),id,key,r));
    }
    @GetMapping("/registration-sessions/{id}") public ResponseEntity<BillingViews.Session> session(Principal p,@PathVariable String id) { return ok(billing.session(user(p),id)); }
    @PostMapping("/registration-sessions/{id}/cancel") public ResponseEntity<BillingViews.Session> cancelRegistration(Principal p,@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key) { return ok(billing.cancelRegistration(user(p),id,key)); }
    @PostMapping("/subscription/cancel") public ResponseEntity<BillingViews.Subscription> cancel(Principal p,@RequestHeader("Idempotency-Key") String key) { return ok(billing.cancel(user(p),key)); }
    @PostMapping("/subscription/resume") public ResponseEntity<BillingViews.Subscription> resume(Principal p,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody BillingRequests.Resume r) { return ok(billing.resume(user(p),key,r.consentVersion())); }
    @PostMapping("/invoices/{id}/cancel-payment") public ResponseEntity<BillingViews.Refund> cancelPayment(Principal p,@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody BillingRequests.CancelPayment r) {
        var result=normal.ownsInvoice(user(p),id)?normal.refund(user(p),id,key,r.reason()):refunds.cancel(user(p),id,key,r.reason());
        return ResponseEntity.status("CANCELED".equals(result.status())?200:202).cacheControl(CacheControl.noStore()).body(result);
    }
    @PostMapping("/invoices/{id}/retry") public ResponseEntity<BillingViews.Accepted> retry(Principal p,@PathVariable String id,@RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(billing.retry(user(p),id,key));
    }
}
