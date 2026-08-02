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

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPPORT')")
    @GetMapping
    public ResponseEntity<Page<AuditLog>> search(
        @RequestParam(required = false) UUID orgId,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size) {

        // No Sort here: the native search query already ORDERs BY a.created_at DESC. Passing a
        // Pageable Sort would append ", a.createdAt desc" to native SQL (property name, not the
        // column), which Postgres rejects as column "a.createdat".
        return ResponseEntity.ok(service.search(orgId, action, from, to, PageRequest.of(page, size)));
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
}
