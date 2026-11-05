package com.ipgo.server.controller;

import com.ipgo.server.exception.ProductException;
import com.ipgo.server.service.InsightBatch;
import com.ipgo.server.service.InsightService;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class InsightBatchController {
    private final InsightBatch batch;
    private final InsightService insights;
    public InsightBatchController(InsightBatch batch,InsightService insights) { this.batch=batch; this.insights=insights; }

    @GetMapping("/api/insight-batches/{batchId}")
    public InsightBatch.Preview preview(Principal principal,@PathVariable String batchId) {
        return batch.preview(Long.parseLong(principal.getName()),batchId);
    }
    @PostMapping("/api/insight-batches/{batchId}/results/{resultId}/generate")
    public ResponseEntity<?> generate(Principal principal,@PathVariable String batchId,@PathVariable long resultId,
                                      @RequestBody(required=false) JsonNode body) {
        if(body!=null) throw ProductException.invalidInput();
        long owner=Long.parseLong(principal.getName());
        batch.generate(owner,batchId,resultId);
        return ResponseEntity.ok(insights.insight(owner,resultId).orElseThrow(ProductException::insightInputUnavailable));
    }
}
