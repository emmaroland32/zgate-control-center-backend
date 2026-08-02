package com.zgate.controlcenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.domain.*;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.*;
import com.zgate.controlcenter.security.JwtUtils;
import com.zgate.controlcenter.security.TotpService;
import com.zgate.controlcenter.service.provisioning.ProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The hardening layer: operator MFA, real session revocation, four-eyes rollout approval,
 * maintenance-window gating, evidence-based SLA, and alert delivery. Each of these guards a
 * customer-affecting action, so each transition — and each forbidden one — is pinned.
 */
class FleetHardeningTest {

    // ── TOTP ────────────────────────────────────────────────────────────────

    @Nested
    class Totp {
        private final TotpService totp = new TotpService();

        @Test
        @DisplayName("matches the RFC 6238 SHA-1 test vector (t=59s → …287082)")
        void rfcVector() {
            // RFC secret "12345678901234567890" in base32; step 59/30 = 1; 8-digit vector 94287082.
            String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
            assertThat(totp.generateCode(secret, 1)).isEqualTo("287082");
        }

        @Test
        @DisplayName("verifies a code for the current step and rejects garbage")
        void roundTrip() {
            String secret = totp.generateSecret();
            String now = totp.generateCode(secret, System.currentTimeMillis() / 1000 / 30);
            assertThat(totp.verify(secret, now)).isTrue();
            assertThat(totp.verify(secret, "000000")).satisfiesAnyOf(
                ok -> assertThat(ok).isFalse(),                      // overwhelmingly likely
                ok -> assertThat(now).isEqualTo("000000"));          // the 1-in-a-million collision
            assertThat(totp.verify(secret, "not-a-code")).isFalse();
            assertThat(totp.verify(null, now)).isFalse();
        }
    }

    // ── JWT revocation ──────────────────────────────────────────────────────

    @Nested
    class TokenVersion {
        @Test
        @DisplayName("the tv claim round-trips, and legacy tokens without one read as version 0")
        void claimRoundTrip() {
            JwtUtils jwt = new JwtUtils();
            ReflectionTestUtils.setField(jwt, "jwtSecret", "0123456789abcdef0123456789abcdef");
            ReflectionTestUtils.setField(jwt, "jwtExpirationMs", 60_000L);
            String token = jwt.generateToken("op@zgate.io", "ROLE_ADMIN", 7);
            assertThat(jwt.getTokenVersion(token)).isEqualTo(7);
            assertThat(jwt.getEmailFromToken(token)).isEqualTo("op@zgate.io");
        }
    }

    // ── MFA login enforcement + revocation semantics ────────────────────────

    @Nested
    class MfaAndRevocation {
        private ControlCenterUserRepository repo;
        private ControlCenterUserService svc;
        private ControlCenterUser user;

        @BeforeEach
        void setUp() {
            repo = mock(ControlCenterUserRepository.class);
            TotpService totp = new TotpService();
            svc = new ControlCenterUserService(repo,
                mock(org.springframework.security.crypto.password.PasswordEncoder.class), totp);
            ReflectionTestUtils.setField(svc, "lockoutThreshold", 5);
            ReflectionTestUtils.setField(svc, "lockoutBaseMinutes", 1);
            ReflectionTestUtils.setField(svc, "lockoutMaxMinutes", 60L);
            user = ControlCenterUser.builder()
                .id(UUID.randomUUID()).name("Op").email("op@zgate.io")
                .passwordHash("x").role(ControlCenterUser.Role.ADMIN).build();
            when(repo.findByEmail("op@zgate.io")).thenReturn(Optional.of(user));
            when(repo.findById(user.getId())).thenReturn(Optional.of(user));
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("no MFA enrolled → login untouched; enabled + missing code → MFA_REQUIRED")
        void mfaGate() {
            svc.requireMfaIfEnabled("op@zgate.io", null);            // passes silently

            user.setMfaEnabled(true);
            user.setMfaSecret(new TotpService().generateSecret());
            assertThatThrownBy(() -> svc.requireMfaIfEnabled("op@zgate.io", null))
                .isInstanceOfSatisfying(ControlCenterException.class,
                    e -> assertThat(e.getCode()).isEqualTo("MFA_REQUIRED"));
            assertThatThrownBy(() -> svc.requireMfaIfEnabled("op@zgate.io", "123456"))
                .isInstanceOfSatisfying(ControlCenterException.class,
                    e -> assertThat(e.getCode()).isEqualTo("MFA_INVALID"));
        }

        @Test
        @DisplayName("activation demands a valid code; a valid one promotes the staged secret")
        void activation() {
            svc.mfaEnroll("op@zgate.io", null);
            assertThat(user.getMfaPendingSecret()).isNotBlank();
            assertThat(user.getMfaSecret()).isNull();
            assertThat(user.isMfaEnabled()).isFalse();

            assertThatThrownBy(() -> svc.mfaActivate("op@zgate.io", "000001"))
                .isInstanceOf(ControlCenterException.class);

            String code = new TotpService().generateCode(user.getMfaPendingSecret(),
                System.currentTimeMillis() / 1000 / 30);
            svc.mfaActivate("op@zgate.io", code);
            assertThat(user.isMfaEnabled()).isTrue();
            assertThat(user.getMfaSecret()).isNotBlank();
            assertThat(user.getMfaPendingSecret()).isNull();
        }

        @Test
        @DisplayName("re-enrolling CANNOT disable an active factor: no code -> refused, live secret intact")
        void enrollCannotDowngradeMfa() {
            String live = new TotpService().generateSecret();
            user.setMfaEnabled(true);
            user.setMfaSecret(live);

            assertThatThrownBy(() -> svc.mfaEnroll("op@zgate.io", null))
                .isInstanceOfSatisfying(ControlCenterException.class,
                    e -> assertThat(e.getCode()).isEqualTo("MFA_REQUIRED"));

            // The whole point: a session-holding attacker cannot strip the second factor.
            assertThat(user.isMfaEnabled()).isTrue();
            assertThat(user.getMfaSecret()).isEqualTo(live);
            assertThat(user.getMfaPendingSecret()).isNull();

            // With a current code, re-enrollment stages a NEW secret but still leaves MFA on.
            String code = new TotpService().generateCode(live, System.currentTimeMillis() / 1000 / 30);
            svc.mfaEnroll("op@zgate.io", code);
            assertThat(user.isMfaEnabled()).isTrue();
            assertThat(user.getMfaSecret()).isEqualTo(live);
            assertThat(user.getMfaPendingSecret()).isNotBlank().isNotEqualTo(live);
        }

        @Test
        @DisplayName("a TOTP code is single-use: the same code is refused on a second login")
        void codeIsSingleUse() {
            String secret = new TotpService().generateSecret();
            user.setMfaEnabled(true);
            user.setMfaSecret(secret);
            String code = new TotpService().generateCode(secret, System.currentTimeMillis() / 1000 / 30);

            svc.requireMfaIfEnabled("op@zgate.io", code);          // first use: accepted
            assertThat(user.getMfaLastStep()).isNotNull();

            assertThatThrownBy(() -> svc.requireMfaIfEnabled("op@zgate.io", code))
                .isInstanceOfSatisfying(ControlCenterException.class,
                    e -> assertThat(e.getCode()).isEqualTo("MFA_INVALID"));
        }

        @Test
        @DisplayName("lockout: failures accumulate then refuse further attempts; success clears it")
        void lockout() {
            for (int i = 0; i < 5; i++) svc.recordFailedLogin("op@zgate.io");
            assertThat(user.getLockedUntil()).isNotNull();

            assertThatThrownBy(() -> svc.requireNotLockedOut("op@zgate.io"))
                .isInstanceOfSatisfying(ControlCenterException.class,
                    e -> assertThat(e.getCode()).isEqualTo("ACCOUNT_LOCKED"));

            svc.recordSuccessfulLogin("op@zgate.io");
            assertThat(user.getFailedLoginAttempts()).isZero();
            assertThat(user.getLockedUntil()).isNull();
            svc.requireNotLockedOut("op@zgate.io");                // no longer throws
        }

        @Test
        @DisplayName("disable revokes sessions too — deactivating alone left tokens alive for 24h")
        void disableRevokes() {
            int before = user.getTokenVersion();
            svc.disable(user.getId());
            assertThat(user.isActive()).isFalse();
            assertThat(user.getTokenVersion()).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("revoke-sessions bumps the token version; MFA break-glass also revokes")
        void revocation() {
            svc.revokeSessions(user.getId());
            assertThat(user.getTokenVersion()).isEqualTo(1);

            user.setMfaEnabled(true);
            user.setMfaSecret("SECRET");
            svc.mfaDisable(user.getId());
            assertThat(user.isMfaEnabled()).isFalse();
            assertThat(user.getMfaSecret()).isNull();
            assertThat(user.getTokenVersion()).isEqualTo(2);
        }
    }

    // ── Rollout approval + maintenance windows ──────────────────────────────

    @Nested
    class ApprovalAndWindow {
        private FleetRolloutRepository rolloutRepo;
        private FleetRolloutItemRepository itemRepo;
        private OrganizationRepository orgRepo;
        private ProvisioningService provisioning;
        private FleetRolloutOrchestrator orchestrator;
        private FleetRolloutService rolloutService;
        private FleetRollout rollout;
        private FleetRolloutItem item;
        private Organization org;

        @BeforeEach
        void setUp() {
            rolloutRepo = mock(FleetRolloutRepository.class);
            itemRepo = mock(FleetRolloutItemRepository.class);
            orgRepo = mock(OrganizationRepository.class);
            provisioning = mock(ProvisioningService.class);
            orchestrator = new FleetRolloutOrchestrator(rolloutRepo, itemRepo,
                mock(ProvisioningRunRepository.class), mock(InfrastructureStackRepository.class),
                orgRepo, mock(DeploymentRepository.class), provisioning, mock(DeploymentService.class));
            ReflectionTestUtils.setField(orchestrator, "enabled", true);
            rolloutService = new FleetRolloutService(rolloutRepo, itemRepo,
                mock(InfrastructureStackRepository.class), orgRepo, mock(ReleaseRepository.class));

            UUID orgId = UUID.randomUUID();
            org = Organization.builder()
                .id(orgId).name("Acme").slug("acme")
                .tier(Organization.Tier.ENTERPRISE)
                .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
                .deploymentEnv(Organization.DeploymentEnv.PRODUCTION).build();
            when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

            rollout = FleetRollout.builder()
                .id(UUID.randomUUID()).releaseId(UUID.randomUUID()).releaseVersion("2.4.0")
                .status(FleetRollout.Status.IN_PROGRESS)
                .autoApply(true).soakMinutes(0).createdBy("maker").build();
            item = FleetRolloutItem.builder()
                .id(UUID.randomUUID()).rolloutId(rollout.getId()).stackId(UUID.randomUUID())
                .organizationId(orgId).wave(0)
                .status(FleetRolloutItem.Status.PLANNED).planRunId(UUID.randomUUID())
                .toVersion("2.4.0").createdAt(LocalDateTime.now()).build();
            when(rolloutRepo.findById(rollout.getId())).thenReturn(Optional.of(rollout));
            when(rolloutRepo.save(any())).thenAnswer(i -> i.getArgument(0));
            when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));
            when(itemRepo.findByRolloutIdAndWave(rollout.getId(), 0)).thenReturn(List.of(item));
        }

        @Test
        @DisplayName("a rollout awaiting approval does not start — nothing is planned or applied")
        void pendingApprovalFreezes() {
            rollout.setApprovalStatus(FleetRollout.ApprovalStatus.PENDING);
            orchestrator.advance(rollout);
            verifyNoInteractions(provisioning);
            assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.PLANNED);
        }

        @Test
        @DisplayName("the maker cannot approve their own rollout")
        void selfApprovalRefused() {
            rollout.setApprovalStatus(FleetRollout.ApprovalStatus.PENDING);
            assertThatThrownBy(() -> rolloutService.approve(rollout.getId(), "MAKER"))
                .isInstanceOfSatisfying(ControlCenterException.class,
                    e -> assertThat(e.getCode()).isEqualTo("ROLLOUT_SELF_APPROVAL"));

            FleetRollout approved = rolloutService.approve(rollout.getId(), "checker");
            assertThat(approved.getApprovalStatus()).isEqualTo(FleetRollout.ApprovalStatus.APPROVED);
            assertThat(approved.getApprovedBy()).isEqualTo("checker");
        }

        @Test
        @DisplayName("outside the org's maintenance window an auto-apply waits; inside it proceeds")
        void maintenanceWindowGatesAutoApply() {
            // A window that is guaranteed closed right now (the next two hours are outside it,
            // whichever side of midnight we are on).
            LocalTime now = LocalTime.now();
            org.setMaintenanceWindowStart(now.plusHours(2));
            org.setMaintenanceWindowEnd(now.plusHours(3));
            orchestrator.advance(rollout);
            verify(provisioning, never()).apply(any(), any());
            assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.PLANNED);

            // Open the window around "now" — the apply goes out.
            org.setMaintenanceWindowStart(now.minusHours(1));
            org.setMaintenanceWindowEnd(now.plusHours(1));
            ProvisioningRun run = ProvisioningRun.builder().build();
            ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
            run.setStatus(ProvisioningRun.Status.QUEUED);
            run.setAction(ProvisioningRun.Action.APPLY);
            when(provisioning.apply(eq(item.getStackId()), any())).thenReturn(run);
            when(mockDeploymentRepoOf(orchestrator).save(any()))
                .thenAnswer(i -> { Deployment d = i.getArgument(0);
                    ReflectionTestUtils.setField(d, "id", UUID.randomUUID()); return d; });

            orchestrator.advance(rollout);
            assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.APPLYING);
        }

        @Test
        @DisplayName("an invalid timezone fails OPEN — a config typo must not freeze upgrades")
        void badTimezoneFailsOpen() {
            LocalTime now = LocalTime.now();
            org.setMaintenanceWindowStart(now.minusHours(1));
            org.setMaintenanceWindowEnd(now.plusHours(1));
            org.setMaintenanceTimezone("Not/AZone");
            ProvisioningRun run = ProvisioningRun.builder().build();
            ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
            run.setStatus(ProvisioningRun.Status.QUEUED);
            run.setAction(ProvisioningRun.Action.APPLY);
            when(provisioning.apply(eq(item.getStackId()), any())).thenReturn(run);
            when(mockDeploymentRepoOf(orchestrator).save(any()))
                .thenAnswer(i -> { Deployment d = i.getArgument(0);
                    ReflectionTestUtils.setField(d, "id", UUID.randomUUID()); return d; });

            orchestrator.advance(rollout);
            assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.APPLYING);
        }

        private DeploymentRepository mockDeploymentRepoOf(FleetRolloutOrchestrator o) {
            return (DeploymentRepository) ReflectionTestUtils.getField(o, "deploymentRepo");
        }
    }

    // ── SLA ─────────────────────────────────────────────────────────────────

    @Nested
    class Sla {
        @Test
        @DisplayName("uptime = window minus recorded outages; silent-by-design orgs are untracked, not 100%")
        void uptimeFromOutages() {
            OrganizationRepository orgRepo = mock(OrganizationRepository.class);
            AlertRepository alertRepo = mock(AlertRepository.class);
            SlaService svc = new SlaService(orgRepo, alertRepo);

            Organization tracked = Organization.builder()
                .id(UUID.randomUUID()).name("Acme").slug("acme")
                .tier(Organization.Tier.ENTERPRISE)
                .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
                .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
                .lastSeenAt(LocalDateTime.now()).build();
            Organization airGapped = Organization.builder()
                .id(UUID.randomUUID()).name("Bank").slug("bank")
                .tier(Organization.Tier.ENTERPRISE)
                .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
                .deploymentEnv(Organization.DeploymentEnv.PRODUCTION).build();
            when(orgRepo.findAll()).thenReturn(List.of(tracked, airGapped));
            when(alertRepo.findByOrganizationIdAndStatus(any(), any())).thenReturn(List.of());

            // One resolved 24h outage inside the 30-day window → 29/30 uptime ≈ 96.667%.
            Alert outage = Alert.builder()
                .id(UUID.randomUUID()).organizationId(tracked.getId())
                .status(Alert.Status.RESOLVED).severity(AlertRule.Severity.CRITICAL)
                .title("Deployment offline").build();
            outage.setFiredAt(LocalDateTime.now().minusDays(10));
            outage.setResolvedAt(LocalDateTime.now().minusDays(9));
            when(alertRepo.findByOrganizationIdAndStatus(tracked.getId(), Alert.Status.RESOLVED))
                .thenReturn(List.of(outage));

            List<SlaService.OrgSla> out = svc.compute(30);

            SlaService.OrgSla acme = out.stream().filter(o -> o.orgSlug().equals("acme")).findFirst().orElseThrow();
            assertThat(acme.tracked()).isTrue();
            assertThat(acme.uptimePct()).isCloseTo(96.667, org.assertj.core.data.Offset.offset(0.05));
            assertThat(acme.incidentCount()).isEqualTo(1);

            SlaService.OrgSla bank = out.stream().filter(o -> o.orgSlug().equals("bank")).findFirst().orElseThrow();
            assertThat(bank.tracked()).isFalse();
            assertThat(bank.uptimePct()).isNull();
        }
    }

    // ── Alert dispatch ──────────────────────────────────────────────────────

    @Nested
    class AlertDispatch {
        private AlertRepository alertRepo;
        private WebhookRepository webhookRepo;
        private WebhookDeliveryRepository deliveryRepo;
        private JavaMailSender mailSender;
        private AlertDispatchService svc;
        private Alert alert;

        @BeforeEach
        void setUp() {
            alertRepo = mock(AlertRepository.class);
            webhookRepo = mock(WebhookRepository.class);
            deliveryRepo = mock(WebhookDeliveryRepository.class);
            mailSender = mock(JavaMailSender.class);
            OrganizationRepository orgRepo = mock(OrganizationRepository.class);
            // allowInsecureTargets=true so the test can deliver to a local http receiver; in
            // production this validator is what blocks loopback/RFC1918/metadata targets.
            WebhookUrlValidator validator = new WebhookUrlValidator();
            ReflectionTestUtils.setField(validator, "allowInsecureTargets", true);
            svc = new AlertDispatchService(alertRepo, webhookRepo, deliveryRepo, orgRepo,
                                           mailSender, new ObjectMapper(), validator);
            ReflectionTestUtils.setField(svc, "enabled", true);
            ReflectionTestUtils.setField(svc, "notifyEmails", "");
            ReflectionTestUtils.setField(svc, "maxAttempts", 5);
            ReflectionTestUtils.setField(svc, "mailFrom", "");

            alert = Alert.builder()
                .id(UUID.randomUUID()).organizationId(UUID.randomUUID())
                .status(Alert.Status.FIRING).severity(AlertRule.Severity.CRITICAL)
                .title("Deployment offline").message("gone").build();
            alert.setFiredAt(LocalDateTime.now());
            when(alertRepo.findTop50ByStatusAndNotifiedAtNullAndNotifyAttemptsLessThanOrderByFiredAtAsc(
                    eq(Alert.Status.FIRING), eq(5))).thenReturn(List.of(alert));
            when(alertRepo.save(any())).thenAnswer(i -> i.getArgument(0));
            when(orgRepo.findById(any())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("no channels configured at all → delivered (the inbox is the chosen channel)")
        void noChannelsIsDelivered() {
            when(webhookRepo.findByEnabled(true)).thenReturn(List.of());
            svc.dispatchPending();
            assertThat(alert.getNotifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("a webhook 200 marks the alert delivered and records the delivery")
        void webhookDelivery() throws Exception {
            var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            var received = new java.util.concurrent.atomic.AtomicReference<String>();
            server.createContext("/hook", ex -> {
                received.set(new String(ex.getRequestBody().readAllBytes()));
                ex.sendResponseHeaders(200, 0);
                ex.close();
            });
            server.start();
            try {
                Webhook hook = Webhook.builder()
                    .id(UUID.randomUUID()).name("ops")
                    .url("http://127.0.0.1:" + server.getAddress().getPort() + "/hook")
                    .events("[\"ALERT_FIRING\"]").enabled(true).build();
                when(webhookRepo.findByEnabled(true)).thenReturn(List.of(hook));
                when(webhookRepo.save(any())).thenAnswer(i -> i.getArgument(0));
                when(deliveryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

                svc.dispatchPending();

                assertThat(alert.getNotifiedAt()).isNotNull();
                assertThat(received.get()).contains("ALERT_FIRING").contains("Deployment offline");
                org.mockito.ArgumentCaptor<WebhookDelivery> d =
                    org.mockito.ArgumentCaptor.forClass(WebhookDelivery.class);
                verify(deliveryRepo).save(d.capture());
                assertThat(d.getValue().getStatusCode()).isEqualTo(200);
                // The remote response body is deliberately NOT persisted (SSRF read channel).
                assertThat(d.getValue().getResponse()).isNull();
                assertThat(hook.getLastStatus()).isEqualTo("SUCCESS");
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("total failure increments attempts so retry is bounded, not infinite")
        void failureIncrementsAttempts() {
            Webhook hook = Webhook.builder()
                .id(UUID.randomUUID()).name("dead")
                .url("http://127.0.0.1:1/hook")            // nothing listens on port 1
                .events("[\"ALERT_FIRING\"]").enabled(true).build();
            when(webhookRepo.findByEnabled(true)).thenReturn(List.of(hook));
            when(webhookRepo.save(any())).thenAnswer(i -> i.getArgument(0));
            when(deliveryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            svc.dispatchPending();

            assertThat(alert.getNotifiedAt()).isNull();
            assertThat(alert.getNotifyAttempts()).isEqualTo(1);
        }
    }
}
