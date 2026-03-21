package com.zgate.nexus.repository;

import com.zgate.nexus.domain.Partner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PartnerRepository extends JpaRepository<Partner, UUID> {
    List<Partner> findByStatus(Partner.Status status);
    List<Partner> findByTier(Partner.Tier tier);
}
