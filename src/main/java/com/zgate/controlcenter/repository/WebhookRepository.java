package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.Webhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookRepository extends JpaRepository<Webhook, UUID> {
    List<Webhook> findByEnabled(boolean enabled);
}
