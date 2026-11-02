package com.ipgo.server.controller;

import com.ipgo.server.dto.response.CalculationRunStatus;
import com.ipgo.server.service.CalculationRunService;

import java.security.Principal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CalculationRunController {
    private final CalculationRunService runs;

    public CalculationRunController(CalculationRunService runs) {
        this.runs = runs;
    }

    // C2 계산 상태 (완료 / 실패)
    @GetMapping("/api/calculation-runs/{runId}")
    public CalculationRunStatus status(Principal principal, @PathVariable long runId) {
        return runs.status(Long.parseLong(principal.getName()), runId);
    }
}
