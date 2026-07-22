package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.ControlCenterUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ControlCenterUserRepository extends JpaRepository<ControlCenterUser, UUID> {
    Optional<ControlCenterUser> findByEmail(String email);
    boolean existsByEmail(String email);
}
