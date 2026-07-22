package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findByRevokedFalse();

    Optional<ApiKey> findByKeyHash(String hash);
}
