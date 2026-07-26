package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.Deployment;
import com.zgate.controlcenter.payload.request.PushUpdateRequest;
import com.zgate.controlcenter.service.DeploymentService;
import com.zgate.controlcenter.service.ImagePullTokenService;
import com.zgate.controlcenter.service.ImagePullTokenService.PullAuthorization;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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
    private final ImagePullTokenService pullTokenService;
    private final com.zgate.controlcenter.service.AnomalyDetectionService anomalyDetection;

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

    /**
     * Org-facing (authenticated by the X-Control-Center-Org-Id header, like the bundle/telemetry
     * endpoints): the update-agent asks whether — and how — it may pull a release image. CC authorizes
     * only when the org's subscription is valid and the release is within its entitlement, returning
     * the entitled image ref and (if configured) a short-lived pull credential. A denial is 402 so the
     * agent can distinguish "not entitled" from a transport error.
     */
    @PostMapping("/pull-token")
    public ResponseEntity<PullAuthorization> pullToken(
            @RequestHeader("X-Control-Center-Org-Id") UUID orgId,
            @RequestBody(required = false) PullTokenRequest body) {
        PullAuthorization auth = pullTokenService.authorize(orgId, body != null ? body.getVersion() : null);
        return auth.authorized()
            ? ResponseEntity.ok(auth)
            : ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(auth);
    }

    @Data static class PullTokenRequest {
        private String version; // optional; null → latest entitled release
    }

    /** Admin view of an org's live installs (for spotting a copied / over-deployed license). */
    @GetMapping("/org/{orgId}/instances")
    public ResponseEntity<List<com.zgate.controlcenter.domain.OrgInstance>> instances(@PathVariable UUID orgId) {
        return ResponseEntity.ok(anomalyDetection.liveInstances(orgId));
    }

    @Data static class StatusUpdate {
        private Deployment.Status status;
        private String logs;
    }
}
