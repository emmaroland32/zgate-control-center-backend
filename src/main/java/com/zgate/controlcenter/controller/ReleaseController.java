package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.Release;
import com.zgate.controlcenter.payload.request.CreateReleaseRequest;
import com.zgate.controlcenter.service.ReleaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/releases")
@RequiredArgsConstructor
public class ReleaseController {

    private final ReleaseService service;

    @GetMapping
    public ResponseEntity<List<Release>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Release> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @GetMapping("/latest")
    public ResponseEntity<Release> latest() {
        return ResponseEntity.ok(service.getLatestStable());
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Release> publish(@Valid @RequestBody CreateReleaseRequest req,
                                           @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.publish(req, user.getUsername()));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Release> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(service.approve(id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Release> reject(@PathVariable UUID id) {
        return ResponseEntity.ok(service.reject(id));
    }
}
