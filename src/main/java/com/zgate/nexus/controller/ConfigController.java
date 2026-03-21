package com.zgate.nexus.controller;

import com.zgate.nexus.domain.ConfigSnapshot;
import com.zgate.nexus.domain.NexusConfig;
import com.zgate.nexus.service.NexusConfigService;
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

    private final NexusConfigService service;

    @GetMapping
    public ResponseEntity<List<NexusConfig>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{key}")
    public ResponseEntity<NexusConfig> findByKey(@PathVariable String key) {
        return ResponseEntity.ok(service.findByKey(key));
    }

    @GetMapping("/category/{cat}")
    public ResponseEntity<List<NexusConfig>> findByCategory(@PathVariable String cat) {
        return ResponseEntity.ok(service.findByCategory(cat));
    }

    @PutMapping("/{key}")
    public ResponseEntity<NexusConfig> upsert(@PathVariable String key,
                                               @RequestBody Map<String, String> body,
                                               @AuthenticationPrincipal UserDetails user) {
        String updatedBy = body.getOrDefault("updatedBy", user.getUsername());
        return ResponseEntity.ok(service.upsert(key, body.get("value"), updatedBy));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<NexusConfig>> updateBatch(@RequestBody List<Map<String, String>> updates,
                                                          @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.updateBatch(updates, user.getUsername()));
    }

    @PostMapping("/test-registry")
    public ResponseEntity<Map<String, Object>> testRegistry(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.testRegistryConnection(
            body.getOrDefault("url", ""), body.get("username"), body.get("password")));
    }

    @DeleteMapping("/{key}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        service.delete(key);
        return ResponseEntity.noContent().build();
    }

    // ── Snapshots ──────────────────────────────────────────────

    @GetMapping("/snapshots")
    public ResponseEntity<List<ConfigSnapshot>> findAllSnapshots() {
        return ResponseEntity.ok(service.findAllSnapshots());
    }

    @PostMapping("/snapshots")
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
    public ResponseEntity<Void> restoreSnapshot(@PathVariable UUID id,
                                                  @AuthenticationPrincipal UserDetails user) {
        service.restoreSnapshot(id, user.getUsername());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/snapshots/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteSnapshot(@PathVariable UUID id) {
        service.deleteSnapshot(id);
        return ResponseEntity.noContent().build();
    }
}
