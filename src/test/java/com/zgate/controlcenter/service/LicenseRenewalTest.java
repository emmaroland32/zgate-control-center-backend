package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.License;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.repository.LicenseRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.service.LicenseService.RenewalOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The renewal path IS the subscription kill switch: it must extend a paid org's short-lived license
 * (so it never actually lapses), refuse to renew a lapsed org (so its license expires → org locks),
 * and never let a license outlive what the customer has paid for.
 */
class LicenseRenewalTest {

    private LicenseRepository repo;
    private OrganizationRepository orgRepo;
    private LicenseSigningService signing;
    private LicenseService svc;

    private final UUID licenseId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(LicenseRepository.class);
        orgRepo = mock(OrganizationRepository.class);
        signing = mock(LicenseSigningService.class);
        svc = new LicenseService(repo, orgRepo, signing);
        when(signing.isSigningAvailable()).thenReturn(true);
        when(signing.generateSignedBundle(any(), any())).thenReturn("SIGNED");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Organization org(LocalDateTime subscriptionValidUntil, Integer ttlDays, String entitledVersion) {
        return Organization.builder()
            .id(orgId).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .subscriptionValidUntil(subscriptionValidUntil)
            .licenseTtlDays(ttlDays)
            .entitledVersion(entitledVersion)
            .build();
    }

    private License activeLicense(LocalDateTime expiresAt) {
        return License.builder()
            .id(licenseId).organizationId(orgId).moduleName("core")
            .status(License.Status.ACTIVE)
            .expiresAt(expiresAt)
            .build();
    }

    @Test
    @DisplayName("paid org: expiry extended, maxVersion refreshed, bundle re-signed as PENDING")
    void entitledRenews() {
        LocalDateTime now = LocalDateTime.now();
        License l = activeLicense(now.plusDays(2));
        when(repo.findById(licenseId)).thenReturn(Optional.of(l));
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org(now.plusDays(365), 30, "2.0.0")));

        assertThat(svc.renewIfEntitled(licenseId)).isEqualTo(RenewalOutcome.RENEWED);
        assertThat(l.getExpiresAt()).isAfter(now.plusDays(29)).isBefore(now.plusDays(31));
        assertThat(l.getMaxVersion()).isEqualTo("2.0.0");
        assertThat(l.getBundleJson()).isEqualTo("SIGNED");
        assertThat(l.getDeliveryStatus()).isEqualTo(License.DeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("lapsed subscription: NOT renewed (license left to expire → the kill switch)")
    void lapsedIsRefused() {
        LocalDateTime now = LocalDateTime.now();
        License l = activeLicense(now.plusDays(2));
        when(repo.findById(licenseId)).thenReturn(Optional.of(l));
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org(now.minusDays(1), 30, "2.0.0")));

        assertThat(svc.renewIfEntitled(licenseId)).isEqualTo(RenewalOutcome.SKIPPED_NOT_ENTITLED);
        assertThat(l.getExpiresAt()).isEqualTo(now.plusDays(2)); // untouched
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("renewed expiry is capped at the paid-through date (license can't outlive the subscription)")
    void expiryCappedAtSubscription() {
        LocalDateTime now = LocalDateTime.now();
        License l = activeLicense(now.plusDays(1));
        // TTL 30 but subscription ends in 5 days → renew only to +5 (which is still > current +1)
        when(repo.findById(licenseId)).thenReturn(Optional.of(l));
        LocalDateTime paidThrough = now.plusDays(5);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org(paidThrough, 30, null)));

        assertThat(svc.renewIfEntitled(licenseId)).isEqualTo(RenewalOutcome.RENEWED);
        assertThat(l.getExpiresAt()).isEqualTo(paidThrough);
    }

    @Test
    @DisplayName("already covers the paid period → SKIPPED_NOT_DUE, no re-sign")
    void notDueWhenSubscriptionAlreadyCovered() {
        LocalDateTime now = LocalDateTime.now();
        License l = activeLicense(now.plusDays(3));
        when(repo.findById(licenseId)).thenReturn(Optional.of(l));
        // subscription ends in 2 days → cap is now+2, which is NOT after current now+3
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org(now.plusDays(2), 30, null)));

        assertThat(svc.renewIfEntitled(licenseId)).isEqualTo(RenewalOutcome.SKIPPED_NOT_DUE);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("missing / non-active license → SKIPPED_LICENSE_GONE")
    void missingLicense() {
        when(repo.findById(licenseId)).thenReturn(Optional.empty());
        assertThat(svc.renewIfEntitled(licenseId)).isEqualTo(RenewalOutcome.SKIPPED_LICENSE_GONE);
    }

    @Nested
    class Helpers {
        @Test
        void unmanagedOrgIsAlwaysEntitled() {
            assertThat(LicenseService.isEntitled(org(null, null, null), LocalDateTime.now())).isTrue();
        }

        @Test
        void futureSubscriptionEntitledPastSubscriptionNot() {
            LocalDateTime now = LocalDateTime.now();
            assertThat(LicenseService.isEntitled(org(now.plusDays(1), null, null), now)).isTrue();
            assertThat(LicenseService.isEntitled(org(now.minusDays(1), null, null), now)).isFalse();
        }

        @Test
        void ttlDefaultsWhenUnset() {
            assertThat(LicenseService.ttlDays(org(null, null, null)))
                .isEqualTo(LicenseService.DEFAULT_LICENSE_TTL_DAYS);
            assertThat(LicenseService.ttlDays(org(null, 90, null))).isEqualTo(90);
        }

        @Test
        void renewalExpiryUsesTtlWhenSubscriptionFarOut() {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime e = LicenseService.renewalExpiry(org(now.plusDays(365), 30, null), now);
            assertThat(e).isAfter(now.plusDays(29)).isBefore(now.plusDays(31));
        }
    }
}
