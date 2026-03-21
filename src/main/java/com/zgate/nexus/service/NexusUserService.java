package com.zgate.nexus.service;

import com.zgate.nexus.domain.NexusUser;
import com.zgate.nexus.exception.NexusException;
import com.zgate.nexus.payload.request.CreateUserRequest;
import com.zgate.nexus.repository.NexusUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NexusUserService {

    private final NexusUserRepository repo;
    private final PasswordEncoder passwordEncoder;

    public List<NexusUser> findAll() { return repo.findAll(); }

    public NexusUser findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NexusException("User not found: " + id));
    }

    public NexusUser create(CreateUserRequest req) {
        if (repo.existsByEmail(req.getEmail())) {
            throw new NexusException("Email already in use: " + req.getEmail());
        }
        return repo.save(NexusUser.builder()
            .name(req.getName())
            .email(req.getEmail())
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            .role(req.getRole())
            .active(true)
            .build());
    }

    public NexusUser update(UUID id, CreateUserRequest req) {
        NexusUser user = findById(id);
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
        NexusUser user = findById(id);
        user.setActive(false);
        repo.save(user);
    }

    public void revokeSessions(UUID id) {
        NexusUser user = findById(id);
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
