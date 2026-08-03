package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.service.DatabaseService;
import com.zgate.controlcenter.web.ResponseMessage;
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
    private final com.zgate.controlcenter.service.ControlCenterBackupService backups;
    private final com.zgate.controlcenter.service.AuditService audit;
    private final com.zgate.controlcenter.security.ClientIpResolver clientIpResolver;

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

    /**
     * Backups of Control Center's OWN database (pg_dump). Distinct from managed customer backups,
     * which live under /api/v1/admin/backups.
     */
    @GetMapping("/backups")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Map<String, Object>> getBackups() {
        String storageWarning = backups.storageWarning();
        return ResponseEntity.ok(Map.of(
            "available", backups.unavailableReason() == null,
            "unavailableReason", backups.unavailableReason() == null ? "" : backups.unavailableReason(),
            "restoreEnabled", backups.isRestoreEnabled(),
            // Non-empty when this console runs on several replicas but the backup directory is
            // local to this one — the operator needs to know that BEFORE they need a restore.
            "storageWarning", storageWarning == null ? "" : storageWarning,
            "backups", backups.list()));
    }

    @PostMapping("/backups")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "BACKUP_CREATED", value = "Backup written")
    public ResponseEntity<com.zgate.controlcenter.service.ControlCenterBackupService.BackupFile> createBackup(
            @RequestBody(required = false) Map<String, String> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails user,
            jakarta.servlet.http.HttpServletRequest http) {
        var created = backups.create(body == null ? null : body.get("name"));
        audit.log(user.getUsername(), user.getUsername(), "CC_DATABASE_BACKUP_CREATED",
                  "Database", created.id(), null, clientIpResolver.resolve(http),
                  created.sizeBytes() + " bytes",
                  com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(created);
    }

    /** Download a dump. It contains every secret this database holds — SUPER_ADMIN, audited. */
    @GetMapping("/backups/{id}/download")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<org.springframework.core.io.Resource> downloadBackup(
            @PathVariable String id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails user,
            jakarta.servlet.http.HttpServletRequest http) {
        java.nio.file.Path file = backups.resolve(id);
        audit.log(user.getUsername(), user.getUsername(), "CC_DATABASE_BACKUP_DOWNLOADED",
                  "Database", id, null, clientIpResolver.resolve(http),
                  "full control-plane dump exported",
                  com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"")
            .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
            .body(new org.springframework.core.io.FileSystemResource(file));
    }

    /**
     * Restore the control-plane database from a dump. Replaces the live contents, so it needs
     * SUPER_ADMIN, the server-side restoreEnabled flag, AND the backup id echoed as confirmation.
     */
    @PostMapping("/backups/{id}/restore")
    @com.zgate.controlcenter.security.RequiresStepUp
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "BACKUP_RESTORED", value = "Database restored from backup")
    public ResponseEntity<Map<String, String>> restoreBackup(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails user,
            jakarta.servlet.http.HttpServletRequest http) {
        if (!id.equals(body == null ? null : body.get("confirmation"))) {
            throw new com.zgate.controlcenter.exception.ControlCenterException(
                "To restore, confirm with the backup id: '" + id + "'.",
                "RESTORE_CONFIRMATION_REQUIRED", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        audit.log(user.getUsername(), user.getUsername(), "CC_DATABASE_RESTORE_STARTED",
                  "Database", id, null, clientIpResolver.resolve(http),
                  "live control-plane database is being replaced",
                  com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        backups.restore(id);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/backups/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "BACKUP_DELETED", value = "Backup deleted")
    public ResponseEntity<?> deleteBackup(@PathVariable String id) {
        backups.delete(id);
        return ResponseEntity.ok(java.util.Map.of("id", id));
    }
}
