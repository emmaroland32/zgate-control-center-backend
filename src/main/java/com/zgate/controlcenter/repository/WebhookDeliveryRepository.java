package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findTop20ByWebhookIdOrderByFiredAtDesc(UUID webhookId);

    List<WebhookDelivery> findTop50ByOrderByFiredAtDesc();
}
