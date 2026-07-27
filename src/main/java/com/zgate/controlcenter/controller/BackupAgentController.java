package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.BackupRecord;
import com.zgate.controlcenter.service.BackupService;
import com.zgate.controlcenter.web.RawResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Machine-to-machine API for the ZGATE backup agent. Authenticated by {@code ServiceKeyAuthFilter}
 * (X-Control-Center-Org-Id + X-Control-Center-Service-Key); the org is taken from that validated
 * header, never trusted from the body. Responses are {@link RawResponse raw} (no envelope) so the
 * agent parses plain JSON. Control Center never receives the backup bytes — only presigned S3 URLs.
 */
@RestController
@RequestMapping("/api/v1/backups")
@RequiredArgsConstructor
public class BackupAgentController {

    private final BackupService backupService;

    private static final String ORG = "X-Control-Center-Org-Id";

    public record InitiateRequest(Long sizeBytes, String nodeId, String label) {}
    public record CompleteRequest(String sha256, Long sizeBytes) {}
    public record FailRequest(String reason) {}
    public record VerifyReport(boolean verified, String error) {}
    public record RestoreResponse(String downloadUrl, LocalDateTime expiresAt) {}

    /** Reserve a backup slot (entitlement + quota checked) and get a presigned upload URL. */
    @PostMapping("/initiate")
    @RawResponse
    public ResponseEntity<BackupService.InitiateResult> initiate(
            @RequestHeader(ORG) UUID orgId, @RequestBody(required = false) InitiateRequest req) {
        InitiateRequest r = req != null ? req : new InitiateRequest(null, null, null);
        return ResponseEntity.ok(backupService.initiate(orgId, r.sizeBytes(), r.nodeId(), r.label()));
    }

    /** Confirm the ciphertext finished uploading. */
    @PostMapping("/{id}/complete")
    @RawResponse
    public ResponseEntity<BackupRecord> complete(
            @RequestHeader(ORG) UUID orgId, @PathVariable UUID id,
            @RequestBody(required = false) CompleteRequest req) {
        CompleteRequest r = req != null ? req : new CompleteRequest(null, null);
        return ResponseEntity.ok(backupService.complete(orgId, id, r.sha256(), r.sizeBytes()));
    }

    /** Report the result of verifying a completed backup (decrypt + pg_restore --list). */
    @PostMapping("/{id}/verify-report")
    @RawResponse
    public ResponseEntity<BackupRecord> verifyReport(
            @RequestHeader(ORG) UUID orgId, @PathVariable UUID id, @RequestBody VerifyReport req) {
        return ResponseEntity.ok(backupService.recordVerification(orgId, id, req.verified(), req.error()));
    }

    /** Report a failed/aborted upload so the partial object is purged. */
    @PostMapping("/{id}/fail")
    @RawResponse
    public ResponseEntity<Map<String, Object>> fail(
            @RequestHeader(ORG) UUID orgId, @PathVariable UUID id,
            @RequestBody(required = false) FailRequest req) {
        backupService.fail(orgId, id, req != null ? req.reason() : null);
        return ResponseEntity.ok(Map.of("status", "failed", "id", id.toString()));
    }

    /** The calling org's own backups. */
    @GetMapping
    @RawResponse
    public ResponseEntity<List<BackupRecord>> list(@RequestHeader(ORG) UUID orgId) {
        return ResponseEntity.ok(backupService.listForOrg(orgId));
    }

    /** Presigned download URL to restore a completed backup. */
    @PostMapping("/{id}/restore-url")
    @RawResponse
    public ResponseEntity<RestoreResponse> restoreUrl(@RequestHeader(ORG) UUID orgId, @PathVariable UUID id) {
        String url = backupService.restoreUrl(orgId, id);
        return ResponseEntity.ok(new RestoreResponse(url, LocalDateTime.now().plusMinutes(30)));
    }
}
