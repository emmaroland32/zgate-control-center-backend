package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService service;

    /**
     * Audit search. {@code action} and {@code actor} are substring matches; {@code status}
     * (SUCCESS/FAILURE/WARNING) and {@code entityType} are exact. All optional.
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPPORT')")
    @GetMapping
    public ResponseEntity<Page<AuditLog>> search(
        @RequestParam(required = false) UUID orgId,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String actor,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size) {

        if (status != null && !status.isBlank()) {
            try {
                AuditLog.Status.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new com.zgate.controlcenter.exception.ControlCenterException(
                    "status must be one of SUCCESS, FAILURE, WARNING",
                    "VALIDATION_ERROR", org.springframework.http.HttpStatus.BAD_REQUEST);
            }
            status = status.trim().toUpperCase();
        }
        // No Sort here: the native search query already ORDERs BY a.created_at DESC. Passing a
        // Pageable Sort would append ", a.createdAt desc" to native SQL (property name, not the
        // column), which Postgres rejects as column "a.createdat".
        return ResponseEntity.ok(service.search(orgId, action, actor, status, entityType, from, to,
                                                PageRequest.of(Math.max(0, page), clampSize(size))));
    }

    /**
     * Re-derive every row's signature and report mismatches. An "unsigned" row predates signing or
     * was written with no key configured — reported separately rather than counted as valid.
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @GetMapping("/verify")
    public org.springframework.http.ResponseEntity<com.zgate.controlcenter.service.AuditService.VerificationReport>
            verify(@RequestParam(defaultValue = "1000") int limit) {
        return org.springframework.http.ResponseEntity.ok(service.verify(limit));
    }

    static int clampSize(int size) {
        return Math.max(1, Math.min(size, 200));
    }
}
