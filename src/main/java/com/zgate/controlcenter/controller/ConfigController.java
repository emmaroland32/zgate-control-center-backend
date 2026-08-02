package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.ConfigSnapshot;
import com.zgate.controlcenter.domain.ControlCenterConfig;
import com.zgate.controlcenter.service.ControlCenterConfigService;
import com.zgate.controlcenter.web.ResponseMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ControlCenterConfigService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<List<ControlCenterConfig>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{key}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ControlCenterConfig> findByKey(@PathVariable String key) {
        return ResponseEntity.ok(service.findByKey(key));
    }

    @GetMapping("/category/{cat}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<List<ControlCenterConfig>> findByCategory(@PathVariable String cat) {
        return ResponseEntity.ok(service.findByCategory(cat));
    }

    @PutMapping("/{key}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "CONFIG_SAVED", value = "Setting saved")
    public ResponseEntity<ControlCenterConfig> upsert(@PathVariable String key,
                                               @RequestBody Map<String, String> body,
                                               @AuthenticationPrincipal UserDetails user) {
        // Actor is server-derived: honouring a body-supplied updatedBy let an admin attribute a
        // config change to a different operator, forging the console's own audit trail.
        String updatedBy = user.getUsername();
        return ResponseEntity.ok(service.upsert(key, body.get("value"), updatedBy));
    }

    @PostMapping("/batch")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "CONFIG_SAVED", value = "Settings saved")
    public ResponseEntity<List<ControlCenterConfig>> updateBatch(@RequestBody List<Map<String, String>> updates,
                                                          @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.updateBatch(updates, user.getUsername()));
    }

    @PostMapping("/test-registry")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Map<String, Object>> testRegistry(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.testRegistryConnection(
            body.getOrDefault("url", ""), body.get("username"), body.get("password")));
    }

    @DeleteMapping("/{key}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "CONFIG_DELETED", value = "Setting deleted")
    public ResponseEntity<?> delete(@PathVariable String key) {
        service.delete(key);
        return ResponseEntity.ok(java.util.Map.of("key", key));
    }

    // ── Snapshots ──────────────────────────────────────────────

    @GetMapping("/snapshots")
    public ResponseEntity<List<ConfigSnapshot>> findAllSnapshots() {
        return ResponseEntity.ok(service.findAllSnapshots());
    }

    @PostMapping("/snapshots")
    @ResponseMessage(code = "SNAPSHOT_CREATED", value = "Snapshot created")
    public ResponseEntity<ConfigSnapshot> takeSnapshot(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.takeSnapshot(
            body.get("organizationId"),
            user.getUsername(),
            body.get("note")
        ));
    }

    @PostMapping("/snapshots/{id}/restore")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "SNAPSHOT_RESTORED", value = "Snapshot restored")
    public ResponseEntity<?> restoreSnapshot(@PathVariable UUID id,
                                                  @AuthenticationPrincipal UserDetails user) {
        service.restoreSnapshot(id, user.getUsername());
        return ResponseEntity.ok(java.util.Map.of("id", id.toString()));
    }

    @DeleteMapping("/snapshots/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "SNAPSHOT_DELETED", value = "Snapshot deleted")
    public ResponseEntity<?> deleteSnapshot(@PathVariable UUID id) {
        service.deleteSnapshot(id);
        return ResponseEntity.ok(java.util.Map.of("id", id.toString()));
    }
}
