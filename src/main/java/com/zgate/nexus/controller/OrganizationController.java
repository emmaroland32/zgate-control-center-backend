package com.zgate.nexus.controller;

import com.zgate.nexus.domain.Organization;
import com.zgate.nexus.payload.request.CreateOrganizationRequest;
import com.zgate.nexus.service.AuditService;
import com.zgate.nexus.service.OrganizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService service;
    private final AuditService audit;

    @GetMapping
    public ResponseEntity<List<Organization>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard() {
        return ResponseEntity.ok(service.getDashboardSummary());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Organization> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Organization> create(@Valid @RequestBody CreateOrganizationRequest req,
                                               @AuthenticationPrincipal UserDetails user) {
        Organization org = service.create(req);
        audit.log(user.getUsername(), user.getUsername(), "ORG_CREATED", "Organization",
            org.getId().toString(), org.getId(), null, null, com.zgate.nexus.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.status(HttpStatus.CREATED).body(org);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Organization> update(@PathVariable UUID id,
                                               @Valid @RequestBody CreateOrganizationRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Void> updateStatus(@PathVariable UUID id,
                                             @RequestParam Organization.DeploymentStatus status) {
        service.updateStatus(id, status);
        return ResponseEntity.ok().build();
    }
}
