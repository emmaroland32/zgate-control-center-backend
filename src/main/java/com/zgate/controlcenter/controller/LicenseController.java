package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.License;
import com.zgate.controlcenter.payload.request.IssueLicenseRequest;
import com.zgate.controlcenter.service.LicenseService.IssueBulkRequest;
import com.zgate.controlcenter.service.LicenseService;
import com.zgate.controlcenter.service.LicenseService.BundleDelivery;
import com.zgate.controlcenter.service.LicenseService.FingerprintResult;
import com.zgate.controlcenter.service.LicenseService.LicenseBundle;
import com.zgate.controlcenter.service.LicenseService.VerifyResult;
import com.zgate.controlcenter.service.LicenseService.BulkVerifyResult;
import com.zgate.controlcenter.web.ResponseMessage;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/licenses")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService service;

    @GetMapping
    public ResponseEntity<List<License>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/org/{orgId}")
    public ResponseEntity<List<License>> byOrg(@PathVariable UUID orgId) {
        return ResponseEntity.ok(service.findByOrg(orgId));
    }

    @GetMapping("/expiring-soon")
    public ResponseEntity<List<License>> expiringSoon() {
        return ResponseEntity.ok(service.findExpiringSoon());
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        return ResponseEntity.ok(service.getStats());
    }

    @PostMapping("/issue")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "LICENSE_ISSUED", value = "License issued")
    public ResponseEntity<License> issue(@Valid @RequestBody IssueLicenseRequest req,
                                         @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.issue(req, user.getUsername()));
    }

    @PostMapping("/issue-bulk")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "LICENSES_ISSUED", value = "Licenses issued")
    public ResponseEntity<LicenseBundle> issueBulk(@RequestBody IssueBulkRequest req,
                                                    @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.issueBulk(req, user.getUsername()));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "LICENSE_DEACTIVATED", value = "License deactivated")
    public ResponseEntity<License> deactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(service.deactivate(id));
    }

    // ----------------------------------------------------------------
    // Fingerprint
    // ----------------------------------------------------------------

    @GetMapping("/fingerprint")
    public ResponseEntity<FingerprintResult> getFingerprint(
            @RequestParam UUID orgId,
            @RequestParam String moduleName) {
        return ResponseEntity.ok(service.getFingerprint(orgId, moduleName));
    }

    // ----------------------------------------------------------------
    // Activation — JSON payload
    // ----------------------------------------------------------------

    @PostMapping("/activate-json")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "LICENSE_ACTIVATED", value = "License activated")
    public ResponseEntity<License> activateJson(@RequestBody ActivateJsonRequest req,
                                                @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(
            service.activateJson(req.getOrgId(), req.getModuleName(),
                                 req.getLicenseJson(), user.getUsername()));
    }

    // ----------------------------------------------------------------
    // Activation — file upload
    // ----------------------------------------------------------------

    @PostMapping("/activate-file")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "LICENSE_ACTIVATED", value = "License activated")
    public ResponseEntity<License> activateFile(@RequestParam UUID orgId,
                                                @RequestParam String moduleName,
                                                @RequestParam("file") MultipartFile file,
                                                @AuthenticationPrincipal UserDetails user)
            throws IOException {
        return ResponseEntity.ok(
            service.activateFile(orgId, moduleName, file.getBytes(), user.getUsername()));
    }

    // ----------------------------------------------------------------
    // Bundle generation
    // ----------------------------------------------------------------

    @PostMapping("/{id}/generate-bundle")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "LICENSE_BUNDLE_GENERATED", value = "License bundle generated")
    public ResponseEntity<LicenseBundle> generateBundle(@PathVariable UUID id) {
        return ResponseEntity.ok(service.generateBundle(id));
    }

    // ----------------------------------------------------------------
    // Integrity verification
    // ----------------------------------------------------------------

    @PostMapping("/{id}/verify-integrity")
    public ResponseEntity<VerifyResult> verifyIntegrity(@PathVariable UUID id) {
        return ResponseEntity.ok(service.verifyIntegrity(id));
    }

    @PostMapping("/verify-all")
    public ResponseEntity<BulkVerifyResult> verifyAll() {
        return ResponseEntity.ok(service.verifyAllIntegrity());
    }

    // ----------------------------------------------------------------
    // Org-facing endpoints (no JWT — authenticated by X-Control-Center-Org-Id header)
    // ----------------------------------------------------------------

    /** Called by ZGATE org instances to pull their active signed bundles. */
    @GetMapping("/bundle")
    @com.zgate.controlcenter.web.RawResponse // machine-parsed as a JSON array (ControlCenterLicenseSync) — must not be enveloped
    public ResponseEntity<List<BundleDelivery>> fetchBundle(
            @RequestHeader("X-Control-Center-Org-Id") UUID orgId) {
        return ResponseEntity.ok(service.fetchBundlesForOrg(orgId));
    }

    /** Called by ZGATE org instances to report successful/failed activation. */
    @PostMapping("/status-report")
    public ResponseEntity<Void> statusReport(@RequestBody StatusReportRequest req) {
        service.reportActivation(req.getOrgId(), req.getModuleName(),
                                 req.isSuccess(), req.getOrgModulesJson());
        return ResponseEntity.ok().build();
    }

    // ----------------------------------------------------------------
    // Request bodies
    // ----------------------------------------------------------------

    @Data
    static class ActivateJsonRequest {
        private UUID orgId;
        private String moduleName;
        private String licenseJson;
    }

    @Data
    static class StatusReportRequest {
        private UUID orgId;
        private String moduleName;
        private boolean success;
        private String orgModulesJson;
    }
}
