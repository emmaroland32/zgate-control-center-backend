package com.zgate.nexus.repository;

import com.zgate.nexus.domain.NexusUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NexusUserRepository extends JpaRepository<NexusUser, UUID> {
    Optional<NexusUser> findByEmail(String email);
    boolean existsByEmail(String email);
}
