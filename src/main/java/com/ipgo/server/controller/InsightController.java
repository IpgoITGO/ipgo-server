package com.ipgo.server.controller;

import com.ipgo.server.service.InsightService;
import com.ipgo.server.service.InsightRegeneration;

import java.security.Principal;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import tools.jackson.databind.JsonNode;

@RestController
public class InsightController {
    private final InsightService insights;
    private final InsightRegeneration regeneration;

    public InsightController(InsightService insights, InsightRegeneration regeneration) {
        this.insights = insights; this.regeneration = regeneration;
    }

    // K4 AI 인사이트 문장. 아직 생성 중이면 202 {"status": "GENERATING"}
    @GetMapping("/api/results/{resultId}/insight")
    public ResponseEntity<?> insight(Principal principal, @PathVariable long resultId) {
        return insights.insight(Long.parseLong(principal.getName()), resultId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "GENERATING")));
    }

    // Explicit approved exception only; GET remains read-only and cannot initiate generation.
    @GetMapping("/api/results/{resultId}/insight/regeneration")
    public InsightRegeneration.Preview preview(Principal principal, @PathVariable long resultId) {
        return regeneration.preview(Long.parseLong(principal.getName()),resultId);
    }

    @PostMapping("/api/results/{resultId}/insight/regenerate")
    public ResponseEntity<?> regenerate(Principal principal, @PathVariable long resultId,
                                        @RequestBody(required=false) JsonNode body) {
        if(body!=null) throw com.ipgo.server.exception.ProductException.invalidInput();
        regeneration.regenerate(Long.parseLong(principal.getName()),resultId);
        return insight(principal,resultId);
    }
}
