package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.payload.request.CreateOrganizationRequest;
import com.zgate.controlcenter.repository.LicenseRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The service API key is the install's M2M credential: reveal it once, store only a SHA-256 hash. */
class OrganizationServiceKeyTest {

    private OrganizationRepository orgRepo;
    private OrganizationService svc;

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrganizationRepository.class);
        svc = new OrganizationService(orgRepo, mock(LicenseRepository.class));
        when(orgRepo.findBySlug(anyString())).thenReturn(Optional.empty());
        when(orgRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private CreateOrganizationRequest req() {
        CreateOrganizationRequest r = new CreateOrganizationRequest();
        r.setName("Acme"); r.setSlug("acme");
        r.setTier(Organization.Tier.ENTERPRISE);
        r.setDeploymentEnv(Organization.DeploymentEnv.PRODUCTION);
        return r;
    }

    @Test
    @DisplayName("create reveals the raw key once and stores only a SHA-256 hash (not the raw, not MD5)")
    void createRevealsRawKeyAndHashesSha256() {
        Organization org = svc.create(req());
        assertThat(org.getServiceApiKey()).startsWith("zgn_");           // raw key revealed once
        assertThat(org.getServiceApiKeyHash()).hasSize(64);             // SHA-256 hex (MD5 would be 32)
        assertThat(org.getServiceApiKeyHash()).isNotEqualTo(org.getServiceApiKey());
    }

    @Test
    @DisplayName("serviceKeyValid accepts the issued key, rejects wrong/blank")
    void verifiesKey() {
        Organization org = svc.create(req());
        String raw = org.getServiceApiKey();
        UUID id = UUID.randomUUID();
        when(orgRepo.findById(id)).thenReturn(Optional.of(org));

        assertThat(svc.serviceKeyValid(id, raw)).isTrue();
        assertThat(svc.serviceKeyValid(id, "zgn_wrong")).isFalse();
        assertThat(svc.serviceKeyValid(id, null)).isFalse();
    }

    @Test
    @DisplayName("regenerate issues a different key + hash")
    void regenerateRotates() {
        Organization org = svc.create(req());
        String firstHash = org.getServiceApiKeyHash();
        UUID id = UUID.randomUUID();
        when(orgRepo.findById(id)).thenReturn(Optional.of(org));

        Organization rotated = svc.regenerateServiceKey(id);
        assertThat(rotated.getServiceApiKey()).startsWith("zgn_");
        assertThat(rotated.getServiceApiKeyHash()).hasSize(64).isNotEqualTo(firstHash);
    }
}
