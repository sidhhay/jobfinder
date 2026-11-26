package com.jobfinder.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Liveness routes. */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "jobful-api");
    }

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of("status", "jobful-api", "frontend", "next-server");
    }
}
