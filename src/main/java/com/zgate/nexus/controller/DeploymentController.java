package com.zgate.nexus.controller;

import com.zgate.nexus.domain.Deployment;
import com.zgate.nexus.payload.request.PushUpdateRequest;
import com.zgate.nexus.service.DeploymentService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deployments")
@RequiredArgsConstructor
public class DeploymentController {

    private final DeploymentService service;

    @GetMapping
    public ResponseEntity<List<Deployment>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        return ResponseEntity.ok(service.getStats());
    }

    @GetMapping("/org/{orgId}")
    public ResponseEntity<?> byOrg(@PathVariable UUID orgId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.findByOrg(orgId, PageRequest.of(page, size)));
    }

    @PostMapping("/push-update")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<List<Deployment>> push(@Valid @RequestBody PushUpdateRequest req,
                                                  @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.pushUpdate(req, user.getUsername()));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Deployment> updateStatus(@PathVariable UUID id,
                                                    @RequestBody StatusUpdate body) {
        return ResponseEntity.ok(service.updateStatus(id, body.getStatus(), body.getLogs()));
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Deployment> rollback(@PathVariable UUID id,
                                                @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.rollback(id, user.getUsername()));
    }

    @Data static class StatusUpdate {
        private Deployment.Status status;
        private String logs;
    }
}
