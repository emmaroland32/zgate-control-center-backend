package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.payload.request.UpdateEntitlementRequest;
import com.zgate.controlcenter.repository.LicenseRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The admin lever that makes the whole commercial-protection system operable. */
class OrganizationEntitlementTest {

    private OrganizationRepository orgRepo;
    private OrganizationService svc;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrganizationRepository.class);
        svc = new OrganizationService(orgRepo, mock(LicenseRepository.class));
        Organization o = Organization.builder()
            .id(id).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .build();
        when(orgRepo.findById(id)).thenReturn(Optional.of(o));
        when(orgRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("sets all commercial entitlements on the org")
    void setsEntitlements() {
        LocalDateTime until = LocalDateTime.now().plusDays(365);
        UpdateEntitlementRequest req = new UpdateEntitlementRequest();
        req.setSubscriptionValidUntil(until);
        req.setEntitledVersion("2.0.0");
        req.setLicenseTtlDays(30);
        req.setMaxInstances(3);
        req.setDeploymentTier(Organization.DeploymentTier.HIGH_AVAILABILITY);

        Organization out = svc.updateEntitlement(id, req);

        assertThat(out.getSubscriptionValidUntil()).isEqualTo(until);
        assertThat(out.getEntitledVersion()).isEqualTo("2.0.0");
        assertThat(out.getLicenseTtlDays()).isEqualTo(30);
        assertThat(out.getMaxInstances()).isEqualTo(3);
        assertThat(out.getDeploymentTier()).isEqualTo(Organization.DeploymentTier.HIGH_AVAILABILITY);
    }

    @Test
    @DisplayName("null fields clear entitlements back to unmanaged")
    void nullClearsEntitlements() {
        // seed some values first
        UpdateEntitlementRequest set = new UpdateEntitlementRequest();
        set.setMaxInstances(2);
        set.setDeploymentTier(Organization.DeploymentTier.MULTI_REGION);
        svc.updateEntitlement(id, set);

        Organization out = svc.updateEntitlement(id, new UpdateEntitlementRequest()); // all null

        assertThat(out.getMaxInstances()).isNull();
        assertThat(out.getDeploymentTier()).isNull();
        assertThat(out.getSubscriptionValidUntil()).isNull();
    }
}
