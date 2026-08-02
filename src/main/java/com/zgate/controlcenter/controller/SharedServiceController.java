package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.OrgServiceSubscription;
import com.zgate.controlcenter.domain.ServiceUsage;
import com.zgate.controlcenter.domain.SharedService;
import com.zgate.controlcenter.service.SharedServiceManager;
import com.zgate.controlcenter.web.ResponseMessage;
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
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "SERVICE_CREATED", value = "Service created")
        public ResponseEntity<SharedService> create(@RequestBody SharedService service) {
        return ResponseEntity.ok(manager.create(service));
    }

    @PutMapping("/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "SERVICE_UPDATED", value = "Service updated")
        public ResponseEntity<SharedService> update(@PathVariable UUID id, @RequestBody SharedService service) {
        return ResponseEntity.ok(manager.update(id, service));
    }

    // --- Organization subscriptions ---

    /** Fleet quota posture — current-month usage vs limit for every enabled subscription. */
    @GetMapping("/quotas")
    public ResponseEntity<List<com.zgate.controlcenter.service.SharedServiceManager.QuotaRow>> quotas() {
        return ResponseEntity.ok(manager.quotaOverview());
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<List<OrgServiceSubscription>> getAllSubscriptions() {
        return ResponseEntity.ok(manager.getAllSubscriptions());
    }

    @GetMapping("/subscriptions/{orgId}")
    public ResponseEntity<List<OrgServiceSubscription>> getOrgSubscriptions(@PathVariable UUID orgId) {
        return ResponseEntity.ok(manager.getOrgSubscriptions(orgId));
    }

    @PostMapping("/subscriptions/{orgId}/enable")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "SERVICE_ENABLED", value = "Service enabled for organization")
        public ResponseEntity<OrgServiceSubscription> enable(@PathVariable UUID orgId,
                                                          @RequestBody EnableRequest req) {
        return ResponseEntity.ok(manager.enableForOrg(orgId, req.getServiceId(), req.getCallLimit(), req.getEnabledBy()));
    }

    @PostMapping("/subscriptions/{orgId}/disable")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "SERVICE_DISABLED", value = "Service disabled for organization")
        public ResponseEntity<?> disable(@PathVariable UUID orgId, @RequestParam UUID serviceId) {
        manager.disableForOrg(orgId, serviceId);
        return ResponseEntity.ok(java.util.Map.of("orgId", orgId.toString(), "serviceId", serviceId.toString()));
    }

    // --- Usage tracking — called by deployed ZGATE instances (no auth required, uses API key) ---

    @PostMapping("/track")
    public ResponseEntity<Void> track(@RequestBody TrackUsageRequest req,
                                       @RequestHeader(value = "X-Control-Center-Org-Id") UUID orgId) {
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
