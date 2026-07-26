package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.domain.Release;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.ReleaseRepository;
import com.zgate.controlcenter.service.ImagePullTokenService.PullAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Control Center is the image-supply gatekeeper: it must authorize a pull only for a paid-up org and
 * only for a release within its entitlement — so a customer can't obtain a credential for a newer
 * version than they've bought, even if they know its tag.
 */
class ImagePullTokenTest {

    private OrganizationRepository orgRepo;
    private ReleaseRepository releaseRepo;
    private ImagePullTokenService svc;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrganizationRepository.class);
        releaseRepo = mock(ReleaseRepository.class);
        svc = new ImagePullTokenService(orgRepo, releaseRepo);
        ReflectionTestUtils.setField(svc, "versionGranularity", "minor");
    }

    private Organization org(LocalDateTime subscriptionValidUntil, String entitledVersion) {
        return Organization.builder()
            .id(orgId).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .subscriptionValidUntil(subscriptionValidUntil)
            .entitledVersion(entitledVersion)
            .build();
    }

    private Release release(String version, Release.ApprovalStatus status) {
        return Release.builder()
            .id(UUID.randomUUID()).version(version)
            .channel(Release.Channel.STABLE)
            .dockerRegistry("677021196237.dkr.ecr.eu-west-2.amazonaws.com")
            .dockerTag("zgate:" + version)
            .approvalStatus(status)
            .isLatest(true)
            .build();
    }

    @Test
    @DisplayName("paid org, approved release within entitlement → authorized with image ref")
    void authorized() {
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org(LocalDateTime.now().plusDays(30), "1.5.0")));
        when(releaseRepo.findByVersion("1.4.0")).thenReturn(Optional.of(release("1.4.0", Release.ApprovalStatus.APPROVED)));

        PullAuthorization a = svc.authorize(orgId, "1.4.0");
        assertThat(a.authorized()).isTrue();
        assertThat(a.version()).isEqualTo("1.4.0");
        assertThat(a.dockerTag()).isEqualTo("zgate:1.4.0");
        assertThat(a.credentialPassword()).isNull(); // no provider configured
    }

    @Test
    @DisplayName("lapsed subscription → denied subscription_lapsed")
    void lapsedDenied() {
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org(LocalDateTime.now().minusDays(1), "1.5.0")));
        PullAuthorization a = svc.authorize(orgId, "1.4.0");
        assertThat(a.authorized()).isFalse();
        assertThat(a.reason()).isEqualTo("subscription_lapsed");
    }

    @Test
    @DisplayName("release beyond entitled version → denied version_not_entitled (can't pull unpaid upgrade)")
    void overVersionDenied() {
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org(LocalDateTime.now().plusDays(30), "1.0.0")));
        when(releaseRepo.findByVersion("2.0.0")).thenReturn(Optional.of(release("2.0.0", Release.ApprovalStatus.APPROVED)));
        PullAuthorization a = svc.authorize(orgId, "2.0.0");
        assertThat(a.authorized()).isFalse();
        assertThat(a.reason()).isEqualTo("version_not_entitled");
    }

    @Test
    @DisplayName("unapproved release → denied release_not_approved")
    void unapprovedDenied() {
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org(LocalDateTime.now().plusDays(30), "2.0.0")));
        when(releaseRepo.findByVersion("1.4.0")).thenReturn(Optional.of(release("1.4.0", Release.ApprovalStatus.PENDING)));
        assertThat(svc.authorize(orgId, "1.4.0").reason()).isEqualTo("release_not_approved");
    }

    @Test
    @DisplayName("unmanaged org (no entitledVersion) can pull any approved release")
    void unmanagedOrgAllowedAnyApproved() {
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org(null, null)));
        when(releaseRepo.findByIsLatestTrue()).thenReturn(Optional.of(release("9.9.9", Release.ApprovalStatus.APPROVED)));
        assertThat(svc.authorize(orgId, null).authorized()).isTrue();
    }

    @Test
    @DisplayName("configured credential provider → short-lived credential returned")
    void credentialIssuedWhenProviderConfigured() {
        LocalDateTime exp = LocalDateTime.now().plusHours(12);
        RegistryCredentialProvider provider = r ->
            new RegistryCredentialProvider.RegistryCredential("AWS", "secret-token", exp);
        ReflectionTestUtils.setField(svc, "credentialProvider", provider);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org(LocalDateTime.now().plusDays(30), "2.0.0")));
        when(releaseRepo.findByVersion("1.4.0")).thenReturn(Optional.of(release("1.4.0", Release.ApprovalStatus.APPROVED)));

        PullAuthorization a = svc.authorize(orgId, "1.4.0");
        assertThat(a.authorized()).isTrue();
        assertThat(a.credentialUsername()).isEqualTo("AWS");
        assertThat(a.credentialPassword()).isEqualTo("secret-token");
        assertThat(a.credentialExpiresAt()).isEqualTo(exp);
    }

    @Test
    @DisplayName("unknown org → denied org_not_found")
    void unknownOrgDenied() {
        when(orgRepo.findById(orgId)).thenReturn(Optional.empty());
        assertThat(svc.authorize(orgId, "1.0.0").reason()).isEqualTo("org_not_found");
    }
}
