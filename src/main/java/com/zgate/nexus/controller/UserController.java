package com.zgate.nexus.controller;

import com.zgate.nexus.domain.NexusUser;
import com.zgate.nexus.payload.request.CreateUserRequest;
import com.zgate.nexus.service.NexusUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final NexusUserService service;

    @GetMapping
    public ResponseEntity<List<NexusUser>> findAll() { return ResponseEntity.ok(service.findAll()); }

    @GetMapping("/{id}")
    public ResponseEntity<NexusUser> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<NexusUser> create(@Valid @RequestBody CreateUserRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<NexusUser> update(@PathVariable UUID id,
                                            @Valid @RequestBody CreateUserRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        service.disable(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/revoke-sessions")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> revokeSessions(@PathVariable UUID id) {
        service.revokeSessions(id);
        return ResponseEntity.noContent().build();
    }
}
