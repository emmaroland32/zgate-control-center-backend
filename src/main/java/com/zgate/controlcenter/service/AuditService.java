package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repo;

    @Async
    public void log(String actor, String actorEmail, String action, String entityType,
                    String entityId, UUID orgId, String ip, String details, AuditLog.Status status) {
        repo.save(AuditLog.builder()
            .actor(actor)
            .actorEmail(actorEmail)
            .action(action)
            .entityType(entityType)
            .entityId(entityId)
            .organizationId(orgId)
            .ipAddress(ip)
            .details(details)
            .status(status)
            .build());
    }

    public Page<AuditLog> search(UUID orgId, String action, LocalDateTime from,
                                 LocalDateTime to, Pageable pageable) {
        return repo.search(orgId, action, from, to, pageable);
    }
}
