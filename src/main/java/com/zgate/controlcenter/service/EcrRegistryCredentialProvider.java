package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Release;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenResponse;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

/**
 * AWS ECR implementation of {@link RegistryCredentialProvider}. Mints a short-lived (≈12h) ECR pull
 * credential via {@code GetAuthorizationToken}, handed to an entitled org's update agent by
 * {@link ImagePullTokenService} so it can pull only the release it has been authorized for.
 *
 * <p>Opt-in: only active when {@code controlcenter.registry.ecr.enabled=true}. Credentials come from
 * the standard AWS provider chain (instance role / env), so no secrets live here.
 *
 * <p><b>Scoping note:</b> an ECR authorization token is registry-wide (not per-repository) and lasts
 * ~12h — the entitlement gate in {@link ImagePullTokenService} is what actually restricts <em>which</em>
 * release an org may pull. Tightening the token itself to a single repository requires issuing it via
 * STS AssumeRole with a repo-scoped pull policy (a follow-up).
 */
@Component
@ConditionalOnProperty(name = "controlcenter.registry.ecr.enabled", havingValue = "true")
@Slf4j
public class EcrRegistryCredentialProvider implements RegistryCredentialProvider {

    private final String region;

    public EcrRegistryCredentialProvider(@Value("${controlcenter.registry.ecr.region:eu-west-2}") String region) {
        this.region = region;
        log.info("ECR image-pull credential provider enabled (region={})", region);
    }

    @Override
    public RegistryCredential mint(Release release) {
        try (EcrClient ecr = EcrClient.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.create())
                .build()) {
            GetAuthorizationTokenResponse resp = ecr.getAuthorizationToken();
            List<AuthorizationData> data = resp.authorizationData();
            if (data == null || data.isEmpty()) {
                log.warn("ECR returned no authorization data");
                return null;
            }
            AuthorizationData d = data.get(0);
            LocalDateTime expiresAt = d.expiresAt() != null
                ? LocalDateTime.ofInstant(d.expiresAt(), ZoneId.systemDefault())
                : null;
            return decode(d.authorizationToken(), expiresAt);
        } catch (Exception e) {
            // Never let a registry hiccup break the pull-authorization decision — the org still learns
            // whether it is entitled and the image ref; it just doesn't get a fresh credential.
            log.warn("Failed to mint ECR pull credential: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Decode an ECR authorization token (base64 of {@code "AWS:<password>"}) into a credential.
     * Extracted so the (only) non-AWS logic is unit-testable without a live registry.
     */
    static RegistryCredential decode(String base64Token, LocalDateTime expiresAt) {
        if (base64Token == null || base64Token.isBlank()) return null;
        String decoded = new String(Base64.getDecoder().decode(base64Token), StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        if (colon < 0) return null;
        return new RegistryCredential(decoded.substring(0, colon), decoded.substring(colon + 1), expiresAt);
    }
}
