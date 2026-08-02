package com.zgate.controlcenter.service.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.domain.CloudCredential;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.domain.Release;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.ProvisionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SpecRenderer sits on the riskiest seam in the provisioning system: Java code emitting JSON that
 * Terraform's variable contract must accept. The two halves are written independently, so a drifted
 * attribute name surfaces as a plan failure minutes into provisioning a customer's system rather
 * than at build time.
 *
 * <p>These tests pin the shape and the security-relevant decisions. The rendered specs are also
 * written to {@code target/rendered-specs/} so {@code deploy/tests/run.sh contract} can feed them
 * through a real {@code terraform plan} — that is what actually proves the two sides agree.
 */
class SpecRendererTest {

    private SpecRenderer renderer;
    private Organization org;
    private Release release;
    private CloudCredential awsRole;

    private static final UUID ORG_ID = UUID.fromString("3f1c2b7a-9d4e-4a51-b8c3-6e2f0a7d15b9");
    private static final UUID STACK_ID = UUID.fromString("c84a1e30-5b62-47f9-9a1d-2c7e8b40f6d1");

    /**
     * Structurally a PEM key so the contract's shape checks are exercised; cryptographically
     * meaningless, and never used to connect to anything — the contract fixtures are only ever
     * planned, never applied.
     */
    private static final String DUMMY_SSH_KEY =
        "-----BEGIN OPENSSH PRIVATE KEY-----\nbm90LWEtcmVhbC1rZXktZm9yLWNvbnRyYWN0LXRlc3Rz\n-----END OPENSSH PRIVATE KEY-----";

    @BeforeEach
    void setUp() {
        renderer = new SpecRenderer(new ObjectMapper());
        ReflectionTestUtils.setField(renderer, "controlCenterPublicUrl", "https://control.zgate.example");
        ReflectionTestUtils.setField(renderer, "defaultRegistry", "123456789012.dkr.ecr.eu-west-2.amazonaws.com");
        ReflectionTestUtils.setField(renderer, "cosignPublicKey", "");
        ReflectionTestUtils.setField(renderer, "webImageRepository", "");

        org = Organization.builder()
            .id(ORG_ID).name("Apex Capital Management").slug("apex-capital")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.OFFLINE)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .build();

        release = Release.builder()
            .id(UUID.randomUUID()).version("2.4.0").channel(Release.Channel.STABLE)
            .dockerTag("2.4.0").approvalStatus(Release.ApprovalStatus.APPROVED)
            .build();

        awsRole = CloudCredential.builder()
            .id(UUID.randomUUID()).organizationId(ORG_ID).provider("aws")
            .authMode(CloudCredential.AuthMode.AWS_ASSUME_ROLE)
            .displayName("Apex production account")
            .defaultRegion("eu-west-2")
            .awsRoleArn("arn:aws:iam::210987654321:role/ZgateControlCenterProvisioner")
            .awsExternalId("zgate-abc123")
            .enabled(true)
            .build();
    }

    private ProvisionRequest baseRequest(String target) {
        ProvisionRequest r = new ProvisionRequest();
        r.setOrganizationId(ORG_ID);
        r.setTarget(target);
        r.setEnvironment("prod");
        r.setCloudCredentialId(awsRole.getId());
        r.setSize("medium");
        r.setDnsMode("managed");
        r.setDomainName("api.apex-capital.example");
        r.setHostedZoneId("Z04567890ABCDEFGHIJKL");
        r.setAlarmEmails(List.of("ops@eradiux.example"));
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> spec, String key) {
        return (Map<String, Object>) spec.get(key);
    }

    // ── Shape ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("contract shape")
    class Shape {

        @Test
        @DisplayName("emits every top-level block the contract declares")
        void emitsEveryBlock() {
            Map<String, Object> spec = renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID);

            // Missing a block means Terraform falls back to its default, which for `secrets` or
            // `guards` would silently change behaviour rather than fail.
            assertThat(spec).containsOnlyKeys(
                "identity", "cloud", "image", "network", "database", "cache", "compute",
                "dns", "app", "control_center", "backup", "observability", "secrets", "tags", "guards");
        }

        @Test
        @DisplayName("identity carries the org id and a resource-safe slug")
        void identityIsServerDerived() {
            Map<String, Object> spec = renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID);
            Map<String, Object> identity = sub(spec, "identity");

            assertThat(identity.get("org_id")).isEqualTo(ORG_ID.toString());
            assertThat(identity.get("org_slug")).isEqualTo("apex-capital");
            assertThat(identity.get("environment")).isEqualTo("prod");
            assertThat(identity.get("deployment_id")).isEqualTo(STACK_ID.toString());
        }

        @Test
        @DisplayName("cloud.provider matches the target, so the wrong root cannot apply it")
        void providerMatchesTarget() {
            assertThat(sub(renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID), "cloud")
                .get("provider")).isEqualTo("aws");
        }
    }

    // ── Security-relevant decisions ─────────────────────────────────────────

    @Nested
    @DisplayName("security defaults")
    class SecurityDefaults {

        @Test
        @DisplayName("never emits secrets — each stack generates its own")
        void secretsAreEmpty() {
            ProvisionRequest req = baseRequest("aws-ecs");
            Map<String, Object> spec = renderer.render(req, org, release, awsRole, STACK_ID);

            // Control Center holding ZGATE_FIELD_ENCRYPTION_KEY would make this database the most
            // valuable target in the estate. The stacks generate into the customer's secret manager.
            assertThat(sub(spec, "secrets")).isEmpty();
        }

        @Test
        @DisplayName("keeps deletion protection and a final snapshot on a managed database")
        void databaseIsProtected() {
            Map<String, Object> db = sub(renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID), "database");

            assertThat(db.get("deletion_protection")).isEqualTo(true);
            assertThat(db.get("skip_final_snapshot")).isEqualTo(false);
            assertThat(db.get("ssl_mode")).isEqualTo("require");
        }

        @Test
        @DisplayName("never enables destroy from a provision request")
        void destroyIsNeverEnabledHere() {
            ProvisionRequest req = baseRequest("aws-ecs");
            // Even if a caller tries to smuggle it through the overrides escape hatch, the deliberate
            // decommission path is the only thing that should flip this.
            Map<String, Object> guards = sub(renderer.render(req, org, release, awsRole, STACK_ID), "guards");

            assertThat(guards.get("allow_destroy")).isEqualTo(false);
        }

        @Test
        @DisplayName("licence enforcement is on for a Control Center-managed deployment")
        void licenceEnforcementOn() {
            Map<String, Object> app = sub(renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID), "app");
            Map<String, Object> licence = sub(app, "license");

            // Off would mean the subscription kill switch has no effect on the running instance.
            assertThat(licence.get("enforcement_enabled")).isEqualTo(true);
            assertThat(app.get("log_level")).isEqualTo("INFO");
        }

        @Test
        @DisplayName("mirrors the image rather than pointing the customer at vendor infrastructure")
        void imageIsMirrored() {
            Map<String, Object> image = sub(renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID), "image");
            assertThat(image.get("source")).isEqualTo("mirror");
        }

        @Test
        @DisplayName("the image reference comes from the release, not the request")
        void imageIsServerDerived() {
            Map<String, Object> image = sub(renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID), "image");
            @SuppressWarnings("unchecked")
            Map<String, Object> backend = (Map<String, Object>) image.get("backend");

            // A caller cannot ask for an image the org has not paid for by naming a different repo.
            assertThat(backend.get("repository")).isEqualTo("123456789012.dkr.ecr.eu-west-2.amazonaws.com/zgate");
            assertThat(backend.get("tag")).isEqualTo("2.4.0");
        }
    }

    // ── Target-specific ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("per-target handling")
    class PerTarget {

        @Test
        @DisplayName("aws-ec2 is pinned to one node with SSH closed")
        void ec2IsSingleNode() {
            Map<String, Object> compute = sub(renderer.render(baseRequest("aws-ec2"), org, release, awsRole, STACK_ID), "compute");

            // The stack refuses anything else, so sending a value it can honour avoids a
            // precondition failure on a choice the operator never made.
            assertThat(compute.get("desired_count")).isEqualTo(1);
            assertThat(compute.get("autoscaling_target_cpu")).isEqualTo(0);
            assertThat(compute.get("ssh_allowed_cidrs")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("assume-role credentials pass the role through, static keys do not")
        void assumeRoleIsPassedThrough() {
            Map<String, Object> aws = sub(sub(renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID), "cloud"), "aws");
            assertThat(aws.get("assume_role_arn")).isEqualTo("arn:aws:iam::210987654321:role/ZgateControlCenterProvisioner");

            CloudCredential staticKeys = CloudCredential.builder()
                .id(UUID.randomUUID()).organizationId(ORG_ID).provider("aws")
                .authMode(CloudCredential.AuthMode.AWS_STATIC_KEYS)
                .displayName("static").defaultRegion("eu-west-2").enabled(true).build();

            Map<String, Object> aws2 = sub(sub(renderer.render(baseRequest("aws-ecs"), org, release, staticKeys, STACK_ID), "cloud"), "aws");
            // Static keys reach the provider as env vars, never as a variable written to disk.
            assertThat(aws2).doesNotContainKey("assume_role_arn");
        }

        @Test
        @DisplayName("bare metal pulls the image directly — there is no registry to mirror into")
        void baremetalPullsDirectly() {
            CloudCredential ssh = CloudCredential.builder()
                .id(UUID.randomUUID()).organizationId(ORG_ID).provider("baremetal")
                .authMode(CloudCredential.AuthMode.SSH_KEY)
                .displayName("customer server").defaultRegion("lagos-dc1")
                .sshHost("10.20.30.40").sshUser("ubuntu").enabled(true).build();

            ProvisionRequest req = baseRequest("baremetal");
            req.setDnsMode("none");
            req.setDomainName(null);
            req.setHostedZoneId(null);

            Map<String, Object> spec = renderer.render(req, org, release, ssh, STACK_ID);

            assertThat(sub(spec, "image").get("source")).isEqualTo("direct");
            // The SSH connection details come from the credential, never from the request.
            Map<String, Object> bm = sub(sub(spec, "cloud"), "baremetal");
            assertThat(bm.get("host")).isEqualTo("10.20.30.40");
            assertThat(bm.get("user")).isEqualTo("ubuntu");
            // We do not own the customer's network and cannot build one.
            assertThat(sub(spec, "network").get("mode")).isEqualTo("existing");
            // One server.
            assertThat(sub(spec, "compute").get("desired_count")).isEqualTo(1);
            // And still no secrets in the persisted spec.
            assertThat(sub(spec, "secrets")).isEmpty();
            assertThat(renderer.toJson(spec)).doesNotContain("PRIVATE KEY");
        }

        @Test
        @DisplayName("refuses a credential from the wrong cloud")
        void rejectsProviderMismatch() {
            CloudCredential azure = CloudCredential.builder()
                .id(UUID.randomUUID()).organizationId(ORG_ID).provider("azure")
                .authMode(CloudCredential.AuthMode.AZURE_SERVICE_PRINCIPAL)
                .displayName("azure").defaultRegion("uksouth").enabled(true).build();

            assertThatThrownBy(() -> renderer.render(baseRequest("aws-ecs"), org, release, azure, STACK_ID))
                .isInstanceOf(ControlCenterException.class)
                .hasMessageContaining("needs a aws credential");
        }
    }

    // ── Input handling ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("input handling")
    class InputHandling {

        @Test
        @DisplayName("normalises an org slug that would be an invalid resource name")
        void sanitisesSlug() {
            org.setSlug("Apex Capital (UK) Ltd.");
            Map<String, Object> identity = sub(renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID), "identity");

            String slug = (String) identity.get("org_slug");
            assertThat(slug).matches("^[a-z][a-z0-9-]*[a-z0-9]$").hasSizeLessThanOrEqualTo(30);
        }

        @Test
        @DisplayName("refuses a slug that cannot be reduced to a valid name, rather than emitting a broken one")
        void rejectsUnusableSlug() {
            org.setSlug("!!!");
            assertThatThrownBy(() -> renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID))
                .isInstanceOf(ControlCenterException.class)
                .hasMessageContaining("cannot be used as a resource name");
        }

        @Test
        @DisplayName("requires external database details when the mode says external")
        void requiresExternalDatabaseDetails() {
            ProvisionRequest req = baseRequest("aws-ecs");
            req.setDatabaseMode("external");

            assertThatThrownBy(() -> renderer.render(req, org, release, awsRole, STACK_ID))
                .isInstanceOf(ControlCenterException.class)
                .hasMessageContaining("externalDatabase");
        }

        @Test
        @DisplayName("an external database emits the endpoint but never the password")
        void externalDatabaseOmitsPassword() {
            ProvisionRequest req = baseRequest("aws-ecs");
            req.setDatabaseMode("external");
            ProvisionRequest.ExternalDatabase d = new ProvisionRequest.ExternalDatabase();
            d.setHost("db.customer.internal");
            d.setName("zgate");
            d.setUsername("zgate_app");
            d.setPassword("hunter2");
            req.setExternalDatabase(d);

            Map<String, Object> spec = renderer.render(req, org, release, awsRole, STACK_ID);

            assertThat(sub(spec, "database").get("host")).isEqualTo("db.customer.internal");
            // The password is merged in per-run by ProvisioningService and never persisted here.
            assertThat(renderer.toJson(spec)).doesNotContain("hunter2");
        }

        @Test
        @DisplayName("refuses to render when Control Center has no public URL")
        void requiresControlCenterUrl() {
            ReflectionTestUtils.setField(renderer, "controlCenterPublicUrl", "");

            // Otherwise the instance is provisioned pointing at nothing and silently never licences.
            assertThatThrownBy(() -> renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID))
                .isInstanceOf(ControlCenterException.class)
                .hasMessageContaining("publicUrl");
        }

        @Test
        @DisplayName("deep-merges overrides instead of replacing whole blocks")
        void overridesDeepMerge() {
            ProvisionRequest req = baseRequest("aws-ecs");
            req.setOverrides(Map.of("database", Map.of("multi_az", true)));

            Map<String, Object> db = sub(renderer.render(req, org, release, awsRole, STACK_ID), "database");

            // A shallow merge would drop deletion_protection and every other database attribute —
            // the exact failure that would quietly remove the ledger's safety rails.
            assertThat(db.get("multi_az")).isEqualTo(true);
            assertThat(db.get("deletion_protection")).isEqualTo(true);
            assertThat(db.get("mode")).isEqualTo("managed");
        }

        @Test
        @DisplayName("survives a JSON round trip, since stored specs are replayed on every later run")
        void roundTripsThroughJson() {
            Map<String, Object> spec = renderer.render(baseRequest("aws-ecs"), org, release, awsRole, STACK_ID);
            assertThat(renderer.fromJson(renderer.toJson(spec))).isEqualTo(spec);
        }
    }

    // ── Contract fixtures ───────────────────────────────────────────────────

    /**
     * Writes one rendered spec per target to {@code target/rendered-specs/}. These are consumed by
     * {@code deploy/tests/run.sh contract}, which runs a real {@code terraform plan} against each —
     * proving the Java renderer and the Terraform contract actually agree rather than merely looking
     * like they do.
     */
    @Test
    @DisplayName("writes a spec per target for the Terraform contract check")
    void writesFixturesForTerraform() throws Exception {
        Path dir = Paths.get("target", "rendered-specs");
        Files.createDirectories(dir);

        record Case(String target, String provider) {}
        List<Case> cases = List.of(
            new Case("aws-ecs", "aws"),
            new Case("aws-ec2", "aws"),
            new Case("azure-aca", "azure"),
            new Case("gcp-cloudrun", "gcp"),
            new Case("baremetal", "baremetal"));

        for (Case c : cases) {
            CloudCredential cred = switch (c.provider()) {
                case "azure" -> CloudCredential.builder()
                    .id(UUID.randomUUID()).organizationId(ORG_ID).provider("azure")
                    .authMode(CloudCredential.AuthMode.AZURE_SERVICE_PRINCIPAL)
                    .displayName("azure").defaultRegion("uksouth")
                    .azureSubscriptionId("6f1d8b40-3a92-4c57-b0e8-2d94f7a13c65")
                    .azureTenantId("9c2e5a17-4b60-4f83-91d2-7e08a6b45f39")
                    .enabled(true).build();
                case "baremetal" -> CloudCredential.builder()
                    .id(UUID.randomUUID()).organizationId(ORG_ID).provider("baremetal")
                    .authMode(CloudCredential.AuthMode.SSH_KEY)
                    .displayName("customer server").defaultRegion("lagos-dc1")
                    .sshHost("10.20.30.40").sshPort(22).sshUser("ubuntu")
                    .sshHostPublicKey("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF7mQjXqK9pL3vNwR2sYtBc5dEfGhIjKlMnOpQrStUvW")
                    .enabled(true).build();
                case "gcp" -> CloudCredential.builder()
                    .id(UUID.randomUUID()).organizationId(ORG_ID).provider("gcp")
                    .authMode(CloudCredential.AuthMode.GCP_SERVICE_ACCOUNT)
                    .displayName("gcp").defaultRegion("europe-west2")
                    .gcpProjectId("apex-zgate-prod")
                    .enabled(true).build();
                default -> awsRole;
            };

            ProvisionRequest req = baseRequest(c.target());
            if (!"aws".equals(c.provider())) {
                // Container Apps and Cloud Run both terminate TLS on their own hostname, so a
                // custom domain is optional there in a way it is not on AWS.
                req.setDnsMode("none");
                req.setDomainName(null);
                req.setHostedZoneId(null);
            }
            if ("aws-ec2".equals(c.target())) {
                // The stack refuses a production single-node stack with neither an external
                // database nor the backup agent.
                req.setBackupEnabled(true);
            }
            if ("baremetal".equals(c.target())) {
                // Same durability rule; and "managed" TLS here is Caddy + Let's Encrypt, which
                // needs a domain plus an address for renewal warnings rather than a DNS zone.
                req.setBackupEnabled(true);
                req.setDnsMode("managed");
                req.setDomainName("zgate.customer.example");
                req.setHostedZoneId(null);
            }

            Map<String, Object> spec = renderer.render(req, org, release, cred, STACK_ID);

            // The PERSISTED spec deliberately carries no secrets — ProvisioningService merges the
            // run-time-only ones in per run and never stores them. Bare metal genuinely cannot be
            // planned without its SSH key (Terraform's connection block reads it as a variable), so
            // the fixture has to represent what actually reaches Terraform, not what is stored.
            if ("baremetal".equals(c.target())) {
                spec = renderer.deepMerge(spec, Map.of("secrets", Map.of(
                    "ssh_private_key", DUMMY_SSH_KEY,
                    "registry_username", "AWS",
                    "registry_password", "placeholder-short-lived-token")));
            }

            Files.writeString(dir.resolve(c.target() + ".tfvars.json"), renderer.toJson(spec));
        }

        assertThat(dir.resolve("aws-ecs.tfvars.json")).exists();
        assertThat(dir.resolve("gcp-cloudrun.tfvars.json")).exists();
        assertThat(dir.resolve("baremetal.tfvars.json")).exists();
    }
}
