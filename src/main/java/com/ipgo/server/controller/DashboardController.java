package com.ipgo.server.controller;

import com.ipgo.server.dto.response.ReplenishmentNeeds;
import com.ipgo.server.service.DashboardService;

import java.security.Principal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {
    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    // D1 입고 필요 목록. sort: shortage(기본) · risk · amount
    @GetMapping("/api/dashboard/replenishment-needs")
    public ReplenishmentNeeds needs(Principal principal, @RequestParam(defaultValue = "shortage") String sort) {
        return dashboard.needs(Long.parseLong(principal.getName()), sort);
    }
}
