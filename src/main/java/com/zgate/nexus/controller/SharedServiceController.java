package com.zgate.nexus.controller;

import com.zgate.nexus.domain.OrgServiceSubscription;
import com.zgate.nexus.domain.ServiceUsage;
import com.zgate.nexus.domain.SharedService;
import com.zgate.nexus.service.SharedServiceManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shared-services")
@RequiredArgsConstructor
public class SharedServiceController {

    private final SharedServiceManager manager;

    @GetMapping
    public ResponseEntity<List<SharedService>> findAll() {
        return ResponseEntity.ok(manager.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SharedService> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(manager.findById(id));
    }

    @PostMapping
    public ResponseEntity<SharedService> create(@RequestBody SharedService service) {
        return ResponseEntity.ok(manager.create(service));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SharedService> update(@PathVariable UUID id, @RequestBody SharedService service) {
        return ResponseEntity.ok(manager.update(id, service));
    }

    // --- Organization subscriptions ---

    @GetMapping("/subscriptions")
    public ResponseEntity<List<OrgServiceSubscription>> getAllSubscriptions() {
        return ResponseEntity.ok(manager.getAllSubscriptions());
    }

    @GetMapping("/subscriptions/{orgId}")
    public ResponseEntity<List<OrgServiceSubscription>> getOrgSubscriptions(@PathVariable UUID orgId) {
        return ResponseEntity.ok(manager.getOrgSubscriptions(orgId));
    }

    @PostMapping("/subscriptions/{orgId}/enable")
    public ResponseEntity<OrgServiceSubscription> enable(@PathVariable UUID orgId,
                                                          @RequestBody EnableRequest req) {
        return ResponseEntity.ok(manager.enableForOrg(orgId, req.getServiceId(), req.getCallLimit(), req.getEnabledBy()));
    }

    @PostMapping("/subscriptions/{orgId}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID orgId, @RequestParam UUID serviceId) {
        manager.disableForOrg(orgId, serviceId);
        return ResponseEntity.ok().build();
    }

    // --- Usage tracking — called by deployed ZGATE instances (no auth required, uses API key) ---

    @PostMapping("/track")
    public ResponseEntity<Void> track(@RequestBody TrackUsageRequest req,
                                       @RequestHeader(value = "X-Nexus-Org-Id") UUID orgId) {
        manager.trackUsage(orgId, req.getServiceCode(), req.getCallCount(), req.getSuccessCount());
        return ResponseEntity.ok().build();
    }

    // --- Usage reporting ---

    @GetMapping("/usage/{orgId}")
    public ResponseEntity<List<ServiceUsage>> getUsage(
        @PathVariable UUID orgId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(manager.getUsage(orgId, from, to));
    }

    @Data static class EnableRequest {
        private UUID serviceId;
        private Long callLimit;
        private String enabledBy;
    }

    @Data static class TrackUsageRequest {
        private String serviceCode;
        private long callCount;
        private long successCount;
    }
}
