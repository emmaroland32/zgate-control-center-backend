package com.zgate.nexus.service;

import com.zgate.nexus.domain.Organization;
import com.zgate.nexus.exception.NexusException;
import com.zgate.nexus.payload.request.CreateOrganizationRequest;
import com.zgate.nexus.repository.LicenseRepository;
import com.zgate.nexus.repository.OrganizationRepository;
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
            .orElseThrow(() -> new NexusException("Organization not found: " + id));
    }

    public Organization findBySlug(String slug) {
        return orgRepo.findBySlug(slug)
            .orElseThrow(() -> new NexusException("Organization not found: " + slug));
    }

    @CacheEvict(value = "organizations", allEntries = true)
    public Organization create(CreateOrganizationRequest req) {
        if (orgRepo.findBySlug(req.getSlug()).isPresent()) {
            throw new NexusException("Slug already in use: " + req.getSlug());
        }
        // Generate service API key for machine-to-machine calls
        String rawApiKey = generateApiKey();
        String keyHash = org.springframework.util.DigestUtils.md5DigestAsHex(rawApiKey.getBytes());

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
            .serviceApiKeyHash(keyHash)
            .build();
        return orgRepo.save(org);
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
    public void updateStatus(UUID id, Organization.DeploymentStatus status) {
        Organization org = findById(id);
        org.setDeploymentStatus(status);
        orgRepo.save(org);
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
