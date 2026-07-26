package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.CreateOrganizationRequest;
import com.zgate.controlcenter.repository.LicenseRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository orgRepo;
    private final LicenseRepository licenseRepo;

    @Cacheable("organizations")
    public List<Organization> findAll() {
        return orgRepo.findAll();
    }

    public Organization findById(UUID id) {
        return orgRepo.findById(id)
            .orElseThrow(() -> new ControlCenterException("Organization not found: " + id,
                "ORG_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND));
    }

    public Organization findBySlug(String slug) {
        return orgRepo.findBySlug(slug)
            .orElseThrow(() -> new ControlCenterException("Organization not found: " + slug));
    }

    @CacheEvict(value = "organizations", allEntries = true)
    public Organization create(CreateOrganizationRequest req) {
        if (orgRepo.findBySlug(req.getSlug()).isPresent()) {
            throw new ControlCenterException(
                "An organization with the slug '" + req.getSlug() + "' already exists. Choose a different name or slug.",
                "ORG_SLUG_TAKEN", org.springframework.http.HttpStatus.CONFLICT);
        }
        // Generate the machine-to-machine service API key (used by the install to authenticate its
        // callbacks). We store only the SHA-256 hash and reveal the raw key once in this response.
        String rawApiKey = generateApiKey();

        Organization org = Organization.builder()
            .name(req.getName())
            .slug(req.getSlug())
            .contactEmail(req.getContactEmail())
            .contactName(req.getContactName())
            .country(req.getCountry())
            .region(req.getRegion())
            .tier(req.getTier())
            .deploymentStatus(Organization.DeploymentStatus.PROVISIONING)
            .deploymentEnv(req.getDeploymentEnv())
            .backendUrl(req.getBackendUrl())
            .partnerId(req.getPartnerId())
            .serviceApiKeyHash(sha256Hex(rawApiKey))
            .build();
        Organization saved = orgRepo.save(org);
        saved.setServiceApiKey(rawApiKey); // surfaced once (transient — never persisted)
        return saved;
    }

    /** Rotate the org's service API key, returning the new raw key once (only the hash is stored). */
    @CacheEvict(value = "organizations", allEntries = true)
    public Organization regenerateServiceKey(UUID id) {
        Organization org = findById(id);
        String rawApiKey = generateApiKey();
        org.setServiceApiKeyHash(sha256Hex(rawApiKey));
        Organization saved = orgRepo.save(org);
        saved.setServiceApiKey(rawApiKey);
        return saved;
    }

    /** Verify a presented service API key against the org's stored hash (constant-time). */
    public boolean serviceKeyValid(UUID orgId, String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) return false;
        return orgRepo.findById(orgId)
            .map(Organization::getServiceApiKeyHash)
            .filter(hash -> hash != null && !hash.isBlank())
            .map(hash -> java.security.MessageDigest.isEqual(
                hash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                sha256Hex(presentedKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .orElse(false);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new ControlCenterException("SHA-256 unavailable", e);
        }
    }

    @CacheEvict(value = "organizations", allEntries = true)
    public Organization update(UUID id, CreateOrganizationRequest req) {
        Organization org = findById(id);
        org.setName(req.getName());
        org.setContactEmail(req.getContactEmail());
        org.setContactName(req.getContactName());
        org.setCountry(req.getCountry());
        org.setRegion(req.getRegion());
        org.setTier(req.getTier());
        org.setDeploymentEnv(req.getDeploymentEnv());
        org.setBackendUrl(req.getBackendUrl());
        org.setPartnerId(req.getPartnerId());
        return orgRepo.save(org);
    }

    @CacheEvict(value = "organizations", allEntries = true)
    public Organization updateStatus(UUID id, Organization.DeploymentStatus status) {
        Organization org = findById(id);
        org.setDeploymentStatus(status);
        return orgRepo.save(org);
    }

    /** Set the org's commercial entitlements (subscription, entitled version, seats, deployment tier). */
    @CacheEvict(value = "organizations", allEntries = true)
    public Organization updateEntitlement(UUID id,
            com.zgate.controlcenter.payload.request.UpdateEntitlementRequest req) {
        Organization org = findById(id);
        org.setSubscriptionValidUntil(req.getSubscriptionValidUntil());
        org.setEntitledVersion(req.getEntitledVersion());
        org.setLicenseTtlDays(req.getLicenseTtlDays());
        org.setMaxInstances(req.getMaxInstances());
        org.setDeploymentTier(req.getDeploymentTier());
        return orgRepo.save(org);
    }

    public DashboardSummary getDashboardSummary() {
        long total = orgRepo.count();
        long healthy = orgRepo.countByDeploymentStatus(Organization.DeploymentStatus.HEALTHY);
        long degraded = orgRepo.countByDeploymentStatus(Organization.DeploymentStatus.DEGRADED);
        long offline = orgRepo.countByDeploymentStatus(Organization.DeploymentStatus.OFFLINE);
        long production = orgRepo.countByDeploymentEnv(Organization.DeploymentEnv.PRODUCTION);
        long expiringSoon = licenseRepo.findExpiringSoon(java.time.LocalDateTime.now().plusDays(30)).size();
        return new DashboardSummary(total, healthy, degraded, offline, production, expiringSoon);
    }

    private String generateApiKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "zgn_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record DashboardSummary(long total, long healthy, long degraded, long offline,
                                   long production, long expiringSoon) {}
}
