package com.zgate.nexus.controller;

import com.zgate.nexus.service.DatabaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/database")
@RequiredArgsConstructor
public class DatabaseController {

    private final DatabaseService service;

    @GetMapping("/health")
    public ResponseEntity<DatabaseService.DatabaseHealth> getHealth() {
        return ResponseEntity.ok(service.getHealth());
    }

    @GetMapping("/migrations")
    public ResponseEntity<List<DatabaseService.MigrationEntry>> getMigrations() {
        return ResponseEntity.ok(service.getMigrations());
    }

    @PostMapping("/migrations/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> runMigrations() {
        service.runMigrations();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        return ResponseEntity.ok(service.testConnection());
    }

    @PostMapping("/schema/validate")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> validateSchema() {
        return ResponseEntity.ok(service.validateSchema());
    }

    @GetMapping("/backups")
    public ResponseEntity<List<DatabaseService.BackupEntry>> getBackups() {
        return ResponseEntity.ok(service.getBackups());
    }

    @PostMapping("/backups")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<DatabaseService.BackupEntry> createBackup(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.createBackup(body.getOrDefault("name", "backup")));
    }

    @DeleteMapping("/backups/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteBackup(@PathVariable String id) {
        service.deleteBackup(id);
        return ResponseEntity.noContent().build();
    }
}
