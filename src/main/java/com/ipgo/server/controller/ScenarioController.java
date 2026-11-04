package com.ipgo.server.controller;

import com.ipgo.server.dto.response.ScenarioComparison;
import com.ipgo.server.service.ScenarioService;
import com.ipgo.server.service.CustomScenarioService;
import com.ipgo.server.dto.request.CustomScenarioRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.security.Principal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScenarioController {
    private final ScenarioService scenarios;
    private final CustomScenarioService custom;

    public ScenarioController(ScenarioService scenarios, CustomScenarioService custom) {
        this.scenarios = scenarios;
        this.custom = custom;
    }

    // K2 시나리오 비교표 (80 · 100 · 120%)
    @GetMapping("/api/results/{resultId}/scenarios")
    public ScenarioComparison scenarios(Principal principal, @PathVariable long resultId) {
        return scenarios.scenarios(Long.parseLong(principal.getName()), resultId);
    }
    @PostMapping("/api/results/{resultId}/scenarios")
    public ResponseEntity<ScenarioComparison.Scenario> custom(Principal principal, @PathVariable long resultId,
            @Valid @RequestBody CustomScenarioRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(custom.create(Long.parseLong(principal.getName()), resultId, request.qty()));
    }
}
