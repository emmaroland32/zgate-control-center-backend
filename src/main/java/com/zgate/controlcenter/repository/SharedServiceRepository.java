package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.SharedService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SharedServiceRepository extends JpaRepository<SharedService, UUID> {
    Optional<SharedService> findByCode(String code);
    List<SharedService> findByCategory(SharedService.Category category);
    List<SharedService> findByEnabled(boolean enabled);
}
