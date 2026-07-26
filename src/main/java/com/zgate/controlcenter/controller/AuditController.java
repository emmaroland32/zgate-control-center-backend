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
}
