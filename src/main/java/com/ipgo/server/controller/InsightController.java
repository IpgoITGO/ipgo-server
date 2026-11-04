package com.ipgo.server.controller;

import com.ipgo.server.service.InsightService;

import java.security.Principal;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InsightController {
    private final InsightService insights;

    public InsightController(InsightService insights) {
        this.insights = insights;
    }

    // K4 AI 인사이트 문장. 아직 생성 중이면 202 {"status": "GENERATING"}
    @GetMapping("/api/results/{resultId}/insight")
    public ResponseEntity<?> insight(Principal principal, @PathVariable long resultId) {
        return insights.insight(Long.parseLong(principal.getName()), resultId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "GENERATING")));
    }
}
