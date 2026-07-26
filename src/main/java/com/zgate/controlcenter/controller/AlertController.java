package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.Alert;
import com.zgate.controlcenter.domain.AlertRule;
import com.zgate.controlcenter.service.AlertService;
import com.zgate.controlcenter.web.ResponseMessage;
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
    @ResponseMessage(code = "ALERT_RULE_CREATED", value = "Alert rule created")
    public ResponseEntity<AlertRule> createRule(@RequestBody AlertRule rule) {
        return ResponseEntity.ok(service.createRule(rule));
    }

    @PutMapping("/rules/{id}")
    @ResponseMessage(code = "ALERT_RULE_UPDATED", value = "Alert rule updated")
    public ResponseEntity<AlertRule> updateRule(@PathVariable UUID id, @RequestBody AlertRule rule) {
        return ResponseEntity.ok(service.updateRule(id, rule));
    }

    @DeleteMapping("/rules/{id}")
    @ResponseMessage(code = "ALERT_RULE_DELETED", value = "Alert rule deleted")
    public ResponseEntity<?> deleteRule(@PathVariable UUID id) {
        service.deleteRule(id);
        return ResponseEntity.ok(java.util.Map.of("id", id.toString()));
    }

    /** Enable/disable a rule without wiping its other columns (the PUT path required a full body). */
    @PatchMapping("/rules/{id}/toggle")
    @ResponseMessage(code = "ALERT_RULE_TOGGLED", value = "Alert rule updated")
    public ResponseEntity<AlertRule> toggleRule(@PathVariable UUID id) {
        return ResponseEntity.ok(service.toggleRule(id));
    }

    @GetMapping("/active")
    public ResponseEntity<List<Alert>> active() { return ResponseEntity.ok(service.findActive()); }

    @GetMapping
    public ResponseEntity<List<Alert>> all() { return ResponseEntity.ok(service.findAll()); }

    @PostMapping("/{id}/acknowledge")
    @ResponseMessage(code = "ALERT_ACKNOWLEDGED", value = "Alert acknowledged")
    public ResponseEntity<Alert> ack(@PathVariable UUID id, @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.acknowledge(id, user.getUsername()));
    }

    @PostMapping("/{id}/resolve")
    @ResponseMessage(code = "ALERT_RESOLVED", value = "Alert resolved")
    public ResponseEntity<Alert> resolve(@PathVariable UUID id) {
        return ResponseEntity.ok(service.resolve(id));
    }
}
