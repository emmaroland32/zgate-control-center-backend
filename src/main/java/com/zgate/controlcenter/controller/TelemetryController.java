package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.TelemetryEvent;
import com.zgate.controlcenter.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService service;

    // ----------------------------------------------------------------
    // Ingest — called by ZGATE org instances (no auth, uses org API key header)
    // ----------------------------------------------------------------

    /**
     * POST /api/v1/telemetry/ingest
     * Called by org ZGATE instances to ship error/warning/metric events.
     * The org identifies itself via the X-Control-Center-Org-Id header.
     * Accepts a batch to reduce round-trips.
     */
    @PostMapping("/ingest")
    public ResponseEntity<Void> ingest(
            @RequestHeader("X-Control-Center-Org-Id") UUID orgId,
            @RequestHeader(value = "X-Control-Center-Fingerprint", required = false) String fingerprint,
            @RequestHeader(value = "X-Control-Center-Node-Id", required = false) String nodeId,
            @RequestHeader(value = "X-Control-Center-Platform", required = false) String platform,
            @RequestHeader(value = "X-Control-Center-Mem-Used-Mb", required = false) Integer memUsedMb,
            @RequestHeader(value = "X-Control-Center-Mem-Max-Mb", required = false) Integer memMaxMb,
            @RequestHeader(value = "X-Control-Center-Uptime-Sec", required = false) Long uptimeSeconds,
            @RequestBody List<TelemetryEvent> events) {
        service.ingest(orgId, events, fingerprint, nodeId, platform, memUsedMb, memMaxMb, uptimeSeconds);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    // ----------------------------------------------------------------
    // Query — ControlCenter admin UI
    // ----------------------------------------------------------------

    @GetMapping
    public ResponseEntity<Page<TelemetryEvent>> search(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) TelemetryEvent.Level level,
            @RequestParam(required = false) TelemetryEvent.Category category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(service.search(orgId, level, category, from, to, acknowledged, page, size));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        return ResponseEntity.ok(service.getStats());
    }

    @GetMapping("/stats/org/{orgId}")
    public ResponseEntity<?> statsByOrg(@PathVariable UUID orgId) {
        return ResponseEntity.ok(service.getStatsByOrg(orgId));
    }

    /** Fleet-wide licensing/compliance posture for the licensing dashboard. */
    @GetMapping("/license-summary")
    public ResponseEntity<?> licenseSummary() {
        return ResponseEntity.ok(service.licenseAnomalySummary());
    }

    // ----------------------------------------------------------------
    // Acknowledge
    // ----------------------------------------------------------------

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<TelemetryEvent> acknowledge(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.acknowledge(id, user.getUsername()));
    }

    @PostMapping("/acknowledge-all")
    public ResponseEntity<Void> acknowledgeAll(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) TelemetryEvent.Level level,
            @AuthenticationPrincipal UserDetails user) {
        service.acknowledgeAll(orgId, level, user.getUsername());
        return ResponseEntity.ok().build();
    }

    // ----------------------------------------------------------------
    // Export
    // ----------------------------------------------------------------

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) TelemetryEvent.Level level,
            @RequestParam(required = false) TelemetryEvent.Category category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        String csv = service.exportCsv(orgId, level, category, from, to);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=telemetry-export.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
