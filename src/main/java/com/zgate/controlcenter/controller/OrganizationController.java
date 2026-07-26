package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.payload.request.CreateOrganizationRequest;
import com.zgate.controlcenter.payload.request.UpdateEntitlementRequest;
import com.zgate.controlcenter.service.AuditService;
import com.zgate.controlcenter.service.OrganizationService;
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
            org.getId().toString(), org.getId(), null, null, com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
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

    /**
     * Set the org's commercial entitlements — subscription (kill switch), entitled version, seat count,
     * and deployment tier (K8s/ECS/failover pricing). Admin-only and audited; the current values are
     * readable via {@code GET /{id}} and live nodes via {@code GET /api/v1/deployments/org/{id}/instances}.
     */
    @PatchMapping("/{id}/entitlements")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Organization> updateEntitlements(@PathVariable UUID id,
                                                           @RequestBody UpdateEntitlementRequest req,
                                                           @AuthenticationPrincipal UserDetails user) {
        Organization org = service.updateEntitlement(id, req);
        audit.log(user.getUsername(), user.getUsername(), "ORG_ENTITLEMENT_UPDATED", "Organization",
            id.toString(), id, null,
            "tier=" + org.getDeploymentTier() + ", subValidUntil=" + org.getSubscriptionValidUntil()
                + ", entitledVersion=" + org.getEntitledVersion() + ", maxInstances=" + org.getMaxInstances(),
            com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(org);
    }

    /**
     * Rotate the org's machine-to-machine service API key. The raw key is returned ONCE (in
     * {@code serviceApiKey}); configure it on the install as CONTROLCENTER_SERVICE_KEY. Admin-only,
     * audited; the raw key itself is never logged.
     */
    @PostMapping("/{id}/regenerate-key")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Organization> regenerateKey(@PathVariable UUID id,
                                                      @AuthenticationPrincipal UserDetails user) {
        Organization org = service.regenerateServiceKey(id);
        audit.log(user.getUsername(), user.getUsername(), "ORG_SERVICE_KEY_REGENERATED", "Organization",
            id.toString(), id, null, null, com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(org);
    }
}
