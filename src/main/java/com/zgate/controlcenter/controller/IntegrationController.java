package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.ApiKey;
import com.zgate.controlcenter.domain.IntegrationConfig;
import com.zgate.controlcenter.domain.Webhook;
import com.zgate.controlcenter.domain.WebhookDelivery;
import com.zgate.controlcenter.service.IntegrationService;
import com.zgate.controlcenter.service.IntegrationService.CreateApiKeyResult;
import com.zgate.controlcenter.web.ResponseMessage;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService service;

    // ----------------------------------------------------------------
    // Webhooks
    // ----------------------------------------------------------------

    @GetMapping("/webhooks")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<List<Webhook>> findAllWebhooks() {
        return ResponseEntity.ok(service.findAllWebhooks());
    }

    @PostMapping("/webhooks")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "WEBHOOK_CREATED", value = "Webhook created")
    public ResponseEntity<Webhook> createWebhook(@RequestBody CreateWebhookRequest req,
                                                  @AuthenticationPrincipal UserDetails user) {
        Webhook webhook = Webhook.builder()
                .name(req.getName())
                .url(req.getUrl())
                .secretHash(req.getSecret())   // service hashes it before persisting
                .events(req.getEvents())
                .enabled(req.isEnabled())
                .headers(req.getHeaders())
                .fireCount(0L)
                .build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createWebhook(webhook, user.getUsername()));
    }

    @PutMapping("/webhooks/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "WEBHOOK_UPDATED", value = "Webhook updated")
    public ResponseEntity<Webhook> updateWebhook(@PathVariable UUID id,
                                                  @RequestBody CreateWebhookRequest req) {
        Webhook patch = Webhook.builder()
                .name(req.getName())
                .url(req.getUrl())
                .secretHash(req.getSecret())
                .events(req.getEvents())
                .enabled(req.isEnabled())
                .headers(req.getHeaders())
                .build();
        return ResponseEntity.ok(service.updateWebhook(id, patch));
    }

    @PatchMapping("/webhooks/{id}/toggle")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "WEBHOOK_TOGGLED", value = "Webhook toggled")
    public ResponseEntity<?> toggleWebhook(@PathVariable UUID id) {
        service.toggleWebhook(id);
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }

    @DeleteMapping("/webhooks/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "WEBHOOK_DELETED", value = "Webhook deleted")
    public ResponseEntity<?> deleteWebhook(@PathVariable UUID id) {
        service.deleteWebhook(id);
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }

    @GetMapping("/webhooks/logs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<List<WebhookDelivery>> getRecentDeliveries() {
        return ResponseEntity.ok(service.getRecentDeliveries());
    }

    @GetMapping("/webhooks/{id}/logs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<List<WebhookDelivery>> getWebhookLogs(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getWebhookLogs(id));
    }

    // ----------------------------------------------------------------
    // API Keys
    // ----------------------------------------------------------------

    @GetMapping("/api-keys")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<List<ApiKey>> findAllApiKeys() {
        return ResponseEntity.ok(service.findAllApiKeys());
    }

    @PostMapping("/api-keys")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "API_KEY_CREATED", value = "API key created")
    public ResponseEntity<CreateApiKeyResult> createApiKey(@RequestBody CreateApiKeyRequest req,
                                                            @AuthenticationPrincipal UserDetails user) {
        CreateApiKeyResult result = service.createApiKey(
                req.getName(),
                req.getScopes(),
                req.getExpiresAt(),
                user.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/api-keys/{id}/revoke")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "API_KEY_REVOKED", value = "API key revoked")
    public ResponseEntity<?> revokeApiKey(@PathVariable UUID id,
                                              @AuthenticationPrincipal UserDetails user) {
        service.revokeApiKey(id, user.getUsername());
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }

    // ----------------------------------------------------------------
    // 3rd-party Integration Catalog
    // ----------------------------------------------------------------

    @GetMapping
    public ResponseEntity<List<IntegrationConfig>> findAllIntegrations() {
        return ResponseEntity.ok(service.findAllIntegrations());
    }

    @PostMapping("/{id}/toggle")
    @ResponseMessage(code = "INTEGRATION_TOGGLED", value = "Integration toggled")
    public ResponseEntity<IntegrationConfig> toggleIntegration(@PathVariable UUID id) {
        return ResponseEntity.ok(service.toggleIntegration(id));
    }

    @PutMapping("/{id}/config")
    @ResponseMessage(code = "INTEGRATION_UPDATED", value = "Integration configuration updated")
    public ResponseEntity<IntegrationConfig> updateIntegrationConfig(
            @PathVariable UUID id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.updateIntegrationConfig(id, body.get("config")));
    }

    // ----------------------------------------------------------------
    // Request bodies
    // ----------------------------------------------------------------

    @Data
    static class CreateWebhookRequest {
        private String name;
        private String url;
        /** Raw secret — will be SHA-256-hashed by the service before storage */
        private String secret;
        /** JSON array string, e.g. '["DEPLOYMENT_SUCCESS","LICENSE_EXPIRY"]' */
        private String events;
        private boolean enabled = true;
        /** JSON map string of extra headers */
        private String headers;
    }

    @Data
    static class CreateApiKeyRequest {
        private String name;
        private List<String> scopes;
        private LocalDateTime expiresAt;
    }
}
