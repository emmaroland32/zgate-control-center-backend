package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.ApiKey;
import com.zgate.controlcenter.domain.IntegrationConfig;
import com.zgate.controlcenter.domain.Webhook;
import com.zgate.controlcenter.domain.WebhookDelivery;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.ApiKeyRepository;
import com.zgate.controlcenter.repository.IntegrationConfigRepository;
import com.zgate.controlcenter.repository.WebhookDeliveryRepository;
import com.zgate.controlcenter.repository.WebhookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final WebhookRepository webhookRepo;
    private final WebhookDeliveryRepository deliveryRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final IntegrationConfigRepository integrationConfigRepo;
    private final WebhookUrlValidator urlValidator;

    // ----------------------------------------------------------------
    // Webhooks
    // ----------------------------------------------------------------

    public List<Webhook> findAllWebhooks() {
        return webhookRepo.findAll();
    }

    @Transactional
    public Webhook createWebhook(Webhook webhook, String createdBy) {
        // Refuse internal targets up front: Control Center delivers from the host holding cloud
        // and signing credentials, so an internal URL here is an SSRF primitive.
        urlValidator.validate(webhook.getUrl());
        webhook.setCreatedBy(createdBy);
        if (webhook.getSecretHash() != null && !webhook.getSecretHash().isBlank()) {
            // Caller passes the raw secret; we store only its SHA-256 hash
            webhook.setSecretHash(sha256Hex(webhook.getSecretHash()));
        }
        return webhookRepo.save(webhook);
    }

    @Transactional
    public Webhook updateWebhook(UUID id, Webhook patch) {
        Webhook existing = webhookRepo.findById(id)
                .orElseThrow(() -> new ControlCenterException("Webhook not found: " + id));
        urlValidator.validate(patch.getUrl());
        existing.setName(patch.getName());
        existing.setUrl(patch.getUrl());
        existing.setEvents(patch.getEvents());
        existing.setHeaders(patch.getHeaders());
        existing.setEnabled(patch.isEnabled());
        if (patch.getSecretHash() != null && !patch.getSecretHash().isBlank()) {
            existing.setSecretHash(sha256Hex(patch.getSecretHash()));
        }
        return webhookRepo.save(existing);
    }

    @Transactional
    public void toggleWebhook(UUID id) {
        Webhook webhook = webhookRepo.findById(id)
                .orElseThrow(() -> new ControlCenterException("Webhook not found: " + id));
        webhook.setEnabled(!webhook.isEnabled());
        webhookRepo.save(webhook);
    }

    @Transactional
    public void deleteWebhook(UUID id) {
        if (!webhookRepo.existsById(id)) {
            throw new ControlCenterException("Webhook not found: " + id);
        }
        webhookRepo.deleteById(id);
    }

    public List<WebhookDelivery> getWebhookLogs(UUID webhookId) {
        if (!webhookRepo.existsById(webhookId)) {
            throw new ControlCenterException("Webhook not found: " + webhookId);
        }
        return deliveryRepo.findTop20ByWebhookIdOrderByFiredAtDesc(webhookId);
    }

    public List<WebhookDelivery> getRecentDeliveries() {
        return deliveryRepo.findTop50ByOrderByFiredAtDesc();
    }

    // ----------------------------------------------------------------
    // API Keys
    // ----------------------------------------------------------------

    public List<ApiKey> findAllApiKeys() {
        return apiKeyRepo.findAll();
    }

    @Transactional
    public CreateApiKeyResult createApiKey(String name, List<String> scopes,
                                           LocalDateTime expiresAt, String createdBy) {
        // Generate raw key: "zgn_live_" + 64 url-safe base64 chars (48 random bytes → 64 chars)
        byte[] randomBytes = new byte[48];
        new SecureRandom().nextBytes(randomBytes);
        String rawKey = "zgn_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String keyHash   = sha256Hex(rawKey);
        String keyPrefix = rawKey.substring(0, Math.min(20, rawKey.length()));

        // Serialise scopes as a JSON array string
        String scopesJson = toJsonArray(scopes);

        ApiKey apiKey = ApiKey.builder()
                .name(name)
                .keyHash(keyHash)
                .keyPrefix(keyPrefix)
                .scopes(scopesJson)
                .expiresAt(expiresAt)
                .revoked(false)
                .createdBy(createdBy)
                .build();

        ApiKey saved = apiKeyRepo.save(apiKey);
        return new CreateApiKeyResult(saved, rawKey);
    }

    @Transactional
    public void revokeApiKey(UUID id, String revokedBy) {
        ApiKey key = apiKeyRepo.findById(id)
                .orElseThrow(() -> new ControlCenterException("API key not found: " + id));
        if (key.isRevoked()) {
            throw new ControlCenterException("API key is already revoked: " + id);
        }
        key.setRevoked(true);
        key.setRevokedAt(LocalDateTime.now());
        key.setRevokedBy(revokedBy);
        apiKeyRepo.save(key);
    }

    // ----------------------------------------------------------------
    // Integration Catalog
    // ----------------------------------------------------------------

    public List<IntegrationConfig> findAllIntegrations() {
        return integrationConfigRepo.findAll();
    }

    @Transactional
    public IntegrationConfig toggleIntegration(UUID id) {
        IntegrationConfig config = integrationConfigRepo.findById(id)
                .orElseThrow(() -> new ControlCenterException("Integration not found: " + id));
        config.setEnabled(!config.isEnabled());
        return integrationConfigRepo.save(config);
    }

    @Transactional
    public IntegrationConfig updateIntegrationConfig(UUID id, String configJson) {
        IntegrationConfig config = integrationConfigRepo.findById(id)
                .orElseThrow(() -> new ControlCenterException("Integration not found: " + id));
        config.setConfigJson(configJson);
        return integrationConfigRepo.save(config);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new ControlCenterException("SHA-256 algorithm not available", e);
        }
    }

    /** Minimal JSON array serialiser — avoids pulling in Jackson just for this utility. */
    private String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append('"').append(items.get(i).replace("\"", "\\\"")).append('"');
            if (i < items.size() - 1) sb.append(',');
        }
        sb.append(']');
        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Return type for createApiKey — raw key shown once to the caller
    // ----------------------------------------------------------------

    public record CreateApiKeyResult(ApiKey apiKey, String rawKey) {}
}
