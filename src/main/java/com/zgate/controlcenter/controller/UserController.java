package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.ControlCenterUser;
import com.zgate.controlcenter.payload.request.CreateUserRequest;
import com.zgate.controlcenter.service.ControlCenterUserService;
import com.zgate.controlcenter.web.ResponseMessage;
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

    private final ControlCenterUserService service;

    @GetMapping
    public ResponseEntity<List<ControlCenterUser>> findAll() { return ResponseEntity.ok(service.findAll()); }

    @GetMapping("/{id}")
    public ResponseEntity<ControlCenterUser> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "USER_CREATED", value = "User created")
    public ResponseEntity<ControlCenterUser> create(@Valid @RequestBody CreateUserRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "USER_UPDATED", value = "User updated")
    public ResponseEntity<ControlCenterUser> update(@PathVariable UUID id,
                                            @Valid @RequestBody CreateUserRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "USER_DISABLED", value = "User disabled")
    public ResponseEntity<?> disable(@PathVariable UUID id) {
        service.disable(id);
        return ResponseEntity.ok(java.util.Map.of("id", id.toString()));
    }

    @PostMapping("/{id}/revoke-sessions")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "USER_SESSIONS_REVOKED", value = "Sessions revoked")
    public ResponseEntity<?> revokeSessions(@PathVariable UUID id) {
        service.revokeSessions(id);
        return ResponseEntity.ok(java.util.Map.of("id", id.toString()));
    }
}
