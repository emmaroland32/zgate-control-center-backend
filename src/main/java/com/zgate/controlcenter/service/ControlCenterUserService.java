package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.ControlCenterUser;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.CreateUserRequest;
import com.zgate.controlcenter.repository.ControlCenterUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ControlCenterUserService {

    private final ControlCenterUserRepository repo;
    private final PasswordEncoder passwordEncoder;

    public List<ControlCenterUser> findAll() { return repo.findAll(); }

    public ControlCenterUser findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ControlCenterException("User not found: " + id));
    }

    public ControlCenterUser create(CreateUserRequest req) {
        if (repo.existsByEmail(req.getEmail())) {
            throw new ControlCenterException("Email already in use: " + req.getEmail());
        }
        return repo.save(ControlCenterUser.builder()
            .name(req.getName())
            .email(req.getEmail())
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            .role(req.getRole())
            .active(true)
            .build());
    }

    public ControlCenterUser update(UUID id, CreateUserRequest req) {
        ControlCenterUser user = findById(id);
        if (req.getName() != null && !req.getName().isBlank()) {
            user.setName(req.getName());
        }
        if (req.getRole() != null) {
            user.setRole(req.getRole());
        }
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        }
        return repo.save(user);
    }

    public void disable(UUID id) {
        ControlCenterUser user = findById(id);
        user.setActive(false);
        repo.save(user);
    }

    public void revokeSessions(UUID id) {
        ControlCenterUser user = findById(id);
        // Invalidate by updating lastLoginAt to null — forces re-authentication
        user.setLastLoginAt(null);
        repo.save(user);
    }

    public void recordLogin(String email) {
        repo.findByEmail(email).ifPresent(u -> {
            u.setLastLoginAt(LocalDateTime.now());
            repo.save(u);
        });
    }
}
