package com.ipgo.server.controller;

import com.ipgo.server.dto.response.ScenarioComparison;
import com.ipgo.server.service.ScenarioService;

import java.security.Principal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScenarioController {
    private final ScenarioService scenarios;

    public ScenarioController(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    // K2 시나리오 비교표 (80 · 100 · 120%)
    @GetMapping("/api/results/{resultId}/scenarios")
    public ScenarioComparison scenarios(Principal principal, @PathVariable long resultId) {
        return scenarios.scenarios(Long.parseLong(principal.getName()), resultId);
    }
}
