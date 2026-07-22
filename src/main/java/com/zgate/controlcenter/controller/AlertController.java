package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.Alert;
import com.zgate.controlcenter.domain.AlertRule;
import com.zgate.controlcenter.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService service;

    @GetMapping("/rules")
    public ResponseEntity<List<AlertRule>> rules() { return ResponseEntity.ok(service.findAllRules()); }

    @PostMapping("/rules")
    public ResponseEntity<AlertRule> createRule(@RequestBody AlertRule rule) {
        return ResponseEntity.ok(service.createRule(rule));
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<AlertRule> updateRule(@PathVariable UUID id, @RequestBody AlertRule rule) {
        return ResponseEntity.ok(service.updateRule(id, rule));
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        service.deleteRule(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/active")
    public ResponseEntity<List<Alert>> active() { return ResponseEntity.ok(service.findActive()); }

    @GetMapping
    public ResponseEntity<List<Alert>> all() { return ResponseEntity.ok(service.findAll()); }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Alert> ack(@PathVariable UUID id, @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.acknowledge(id, user.getUsername()));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Alert> resolve(@PathVariable UUID id) {
        return ResponseEntity.ok(service.resolve(id));
    }
}
