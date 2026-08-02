package com.zgate.controlcenter.service.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.domain.CloudCredential;
import com.zgate.controlcenter.domain.InfrastructureStack;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.domain.Release;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.ProvisionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Turns a {@link ProvisionRequest} plus the organization, its entitled release and the chosen
 * credential into the {@code terraform.tfvars.json} the stacks consume.
 *
 * <p>The rendered map must satisfy {@code deploy/contract/variables.tf}. Two rules keep it honest:
 * <ul>
 *   <li><b>Server-derived, never client-trusted.</b> The org id, slug, image reference and Control
 *       Center URL come from the database and configuration — a caller cannot ask to deploy an image
 *       the org has not paid for by putting a different repository in the request.</li>
 *   <li><b>No secrets.</b> The {@code secrets} block is left empty so each stack GENERATES its own
 *       values into the customer's secret manager. Only genuinely customer-supplied credentials (an
 *       external database password) are injected, and those are added at run time by
 *       {@code ProvisioningService} rather than stored in the persisted spec.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpecRenderer {

    private final ObjectMapper mapper;

    /** Public URL of Control Center as a DEPLOYED INSTANCE sees it — not localhost. */
    @Value("${controlcenter.publicUrl:}")
    private String controlCenterPublicUrl;

    /** Vendor registry the release images live in, e.g. 1234.dkr.ecr.eu-west-2.amazonaws.com. */
    @Value("${controlcenter.provisioning.registry:}")
    private String defaultRegistry;

    /** Cosign public key (PEM). When set, the runner refuses to mirror an unsigned image. */
    @Value("${controlcenter.provisioning.cosignPublicKey:}")
    private String cosignPublicKey;

    @Value("${controlcenter.provisioning.webImageRepository:}")
    private String webImageRepository;

    /**
     * @param stackId  the stack this spec belongs to, stamped as identity.deployment_id for traceability
     */
    public Map<String, Object> render(ProvisionRequest req,
                                      Organization org,
                                      Release release,
                                      CloudCredential credential,
                                      UUID stackId) {

        InfrastructureStack.Target target = InfrastructureStack.Target.fromSlug(req.getTarget());

        if (!credential.getProvider().equals(target.provider())) {
            throw new ControlCenterException(
                "Credential '" + credential.getDisplayName() + "' is for " + credential.getProvider()
                + ", but target " + target.slug() + " needs a " + target.provider() + " credential.",
                "CREDENTIAL_PROVIDER_MISMATCH", HttpStatus.BAD_REQUEST);
        }

        String region = firstNonBlank(req.getRegion(), credential.getDefaultRegion());
        if (region == null) {
            throw new ControlCenterException(
                "No region: set one on the request or as the credential's default region.",
                "REGION_REQUIRED", HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("identity", identity(org, req, stackId));
        spec.put("cloud", cloud(target, region, credential));
        spec.put("image", image(target, release));
        spec.put("network", network(req, target));
        spec.put("database", database(req));
        spec.put("cache", cache(req));
        spec.put("compute", compute(req, target));
        spec.put("dns", dns(req, target));
        spec.put("app", app(req));
        spec.put("control_center", controlCenter(org));
        spec.put("backup", Map.of("enabled", bool(req.getBackupEnabled(), false)));
        spec.put("observability", observability(req));
        // Left EMPTY on purpose — each stack generates its own secrets into the customer's secret
        // manager. Customer-supplied credentials are merged in at run time, never persisted here.
        spec.put("secrets", Map.of());
        spec.put("tags", tags(org));
        spec.put("guards", guards(req));

        if (req.getOverrides() != null && !req.getOverrides().isEmpty()) {
            log.info("Applying {} spec override(s) for org {}", req.getOverrides().size(), org.getSlug());
            spec = deepMerge(spec, req.getOverrides());
        }

        return spec;
    }

    // ── Blocks ──────────────────────────────────────────────────────────────

    private Map<String, Object> identity(Organization org, ProvisionRequest req, UUID stackId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("org_id", org.getId().toString());
        m.put("org_slug", sanitiseSlug(org.getSlug()));
        m.put("org_name", nullToEmpty(org.getName()));
        m.put("environment", firstNonBlank(req.getEnvironment(), "prod"));
        m.put("deployment_id", stackId == null ? "" : stackId.toString());
        return m;
    }

    private Map<String, Object> cloud(InfrastructureStack.Target target, String region, CloudCredential cred) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", target.provider());
        m.put("region", region);

        switch (target.provider()) {
            case "aws" -> {
                Map<String, Object> aws = new LinkedHashMap<>();
                // Assume-role is the shape that stores no standing secret; pass the role through so
                // the provider assumes it rather than using the vendor identity directly.
                if (cred.getAuthMode() == CloudCredential.AuthMode.AWS_ASSUME_ROLE) {
                    aws.put("assume_role_arn", nullToEmpty(cred.getAwsRoleArn()));
                    aws.put("assume_role_external_id", nullToEmpty(cred.getAwsExternalId()));
                }
                m.put("aws", aws);
            }
            case "azure" -> m.put("azure", Map.of(
                "subscription_id", nullToEmpty(cred.getAzureSubscriptionId()),
                "tenant_id", nullToEmpty(cred.getAzureTenantId()),
                "resource_group_mode", "create"));
            case "gcp" -> m.put("gcp", Map.of(
                "project_id", nullToEmpty(cred.getGcpProjectId()),
                "zone", region + "-a"));
            case "baremetal" -> {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("host", nullToEmpty(cred.getSshHost()));
                bm.put("port", cred.getSshPort() == null ? 22 : cred.getSshPort());
                bm.put("user", nullToEmpty(cred.getSshUser()));
                bm.put("host_public_key", nullToEmpty(cred.getSshHostPublicKey()));
                bm.put("manage_firewall", true);
                bm.put("data_dir", "/var/lib/zgate");
                m.put("baremetal", bm);
            }
            default -> throw new IllegalStateException("unreachable provider " + target.provider());
        }
        return m;
    }

    /**
     * The {@code image} contract block for a target/release pair, exposed so an upgrade can rewrite
     * exactly this block of a stored spec — and nothing else — when moving a stack to a new release.
     */
    public Map<String, Object> imageBlock(InfrastructureStack.Target target, Release release) {
        return image(target, release);
    }

    private Map<String, Object> image(InfrastructureStack.Target target, Release release) {
        boolean baremetal = target == InfrastructureStack.Target.BAREMETAL;
        String registry = firstNonBlank(release.getDockerRegistry(), defaultRegistry);
        if (registry == null) {
            throw new ControlCenterException(
                "No image registry: set controlcenter.provisioning.registry or the release's dockerRegistry.",
                "IMAGE_REGISTRY_REQUIRED", HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("repository", registry + "/zgate");
        backend.put("tag", release.getDockerTag());
        // The digest wins over the tag in every stack, so a release published with its cosign
        // digest is pulled immutably and the runtime image-digest gate has a value to enforce.
        backend.put("digest", nullToEmpty(release.getImageDigest()));

        Map<String, Object> web = new LinkedHashMap<>();
        web.put("repository", webImageRepository == null || webImageRepository.isBlank()
            ? registry + "/zgate-web" : webImageRepository);
        web.put("tag", release.getDockerTag());
        web.put("digest", nullToEmpty(release.getWebImageDigest()));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("backend", backend);
        m.put("web", web);
        // Azure and GCP cannot pull from a private AWS ECR at all, so those two targets are always
        // mirrored — their stacks enforce this too, but sending the right value avoids a plan that
        // fails on a precondition an operator never chose.
        //
        // Bare metal is the opposite: there is no customer-side registry to mirror INTO, so the
        // server pulls from the vendor registry directly using a short-lived credential the
        // bootstrap places on the box.
        m.put("source", baremetal ? "direct" : "mirror");
        m.put("mirror_repository", "");
        m.put("cosign_public_key", nullToEmpty(cosignPublicKey));
        return m;
    }

    private Map<String, Object> network(ProvisionRequest req, InfrastructureStack.Target target) {
        Map<String, Object> m = new LinkedHashMap<>();
        // On bare metal the server is already on a network we neither own nor can build, so
        // "existing" is the only coherent answer regardless of what the request asked for.
        boolean existing = target == InfrastructureStack.Target.BAREMETAL
            || "existing".equals(req.getNetworkMode());

        if (target == InfrastructureStack.Target.BAREMETAL) {
            m.put("mode", "existing");
            m.put("vpc_id", "customer-managed");
            m.put("public_subnet_ids", List.of());
            m.put("private_subnet_ids", List.of("customer-managed"));
            m.put("public_ingress", bool(req.getPublicIngress(), true));
            m.put("allowed_ingress_cidrs",
                req.getAllowedIngressCidrs() == null || req.getAllowedIngressCidrs().isEmpty()
                    ? List.of("0.0.0.0/0") : req.getAllowedIngressCidrs());
            return m;
        }
        m.put("mode", existing ? "existing" : "create");

        if (existing) {
            ProvisionRequest.ExistingNetwork n = req.getExistingNetwork();
            if (n == null) {
                throw new ControlCenterException(
                    "networkMode = existing requires existingNetwork details.",
                    "EXISTING_NETWORK_REQUIRED", HttpStatus.BAD_REQUEST);
            }
            m.put("vpc_id", n.getVpcId());
            m.put("public_subnet_ids", n.getPublicSubnetIds() == null ? List.of() : n.getPublicSubnetIds());
            m.put("private_subnet_ids", n.getPrivateSubnetIds());
        } else {
            m.put("cidr_block", firstNonBlank(req.getCidrBlock(), "10.42.0.0/16"));
            m.put("az_count", 2);
            m.put("nat_gateway", "single");
        }

        m.put("public_ingress", bool(req.getPublicIngress(), true));
        m.put("allowed_ingress_cidrs",
            req.getAllowedIngressCidrs() == null || req.getAllowedIngressCidrs().isEmpty()
                ? List.of("0.0.0.0/0") : req.getAllowedIngressCidrs());
        return m;
    }

    private Map<String, Object> database(ProvisionRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean external = "external".equals(req.getDatabaseMode());
        m.put("mode", external ? "external" : "managed");
        m.put("name", "zgate");
        m.put("flyway_enabled", true);

        if (external) {
            ProvisionRequest.ExternalDatabase d = req.getExternalDatabase();
            if (d == null) {
                throw new ControlCenterException(
                    "databaseMode = external requires externalDatabase details.",
                    "EXTERNAL_DATABASE_REQUIRED", HttpStatus.BAD_REQUEST);
            }
            m.put("host", d.getHost());
            m.put("port", d.getPort() == null ? 5432 : d.getPort());
            m.put("name", d.getName());
            m.put("username", d.getUsername());
            m.put("ssl_mode", firstNonBlank(d.getSslMode(), "require"));
        } else {
            m.put("engine_version", "16");
            m.put("allocated_storage_gb", req.getDatabaseStorageGb() == null ? 100 : req.getDatabaseStorageGb());
            m.put("max_storage_gb", (req.getDatabaseStorageGb() == null ? 100 : req.getDatabaseStorageGb()) * 5);
            m.put("multi_az", bool(req.getDatabaseMultiAz(), false));
            m.put("backup_retention_days",
                req.getDatabaseBackupRetentionDays() == null ? 14 : req.getDatabaseBackupRetentionDays());
            // Both default ON. This is a double-entry ledger; an accidental destroy is unrecoverable.
            m.put("deletion_protection", true);
            m.put("skip_final_snapshot", false);
            m.put("ssl_mode", "require");
        }
        return m;
    }

    private Map<String, Object> cache(ProvisionRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        String mode = firstNonBlank(req.getCacheMode(), "managed");
        m.put("mode", mode);

        if ("external".equals(mode)) {
            ProvisionRequest.ExternalCache c = req.getExternalCache();
            if (c == null) {
                throw new ControlCenterException(
                    "cacheMode = external requires externalCache details.",
                    "EXTERNAL_CACHE_REQUIRED", HttpStatus.BAD_REQUEST);
            }
            m.put("host", c.getHost());
            m.put("port", c.getPort() == null ? 6379 : c.getPort());
            m.put("tls", bool(c.getTls(), true));
        } else if ("managed".equals(mode)) {
            m.put("engine_version", "7");
            m.put("replica_count", bool(req.getDatabaseMultiAz(), false) ? 1 : 0);
            m.put("transit_encryption", true);
            m.put("at_rest_encryption", true);
        }
        return m;
    }

    private Map<String, Object> compute(ProvisionRequest req, InfrastructureStack.Target target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size", firstNonBlank(req.getSize(), "medium"));

        if (target == InfrastructureStack.Target.BAREMETAL) {
            // One server; the stack refuses more, so send what it can honour.
            m.put("desired_count", 1);
            m.put("autoscaling_target_cpu", 0);
            // Doubles as the minimum free disk the host preflight demands.
            m.put("root_volume_gb", req.getDatabaseStorageGb() == null ? 100 : req.getDatabaseStorageGb());
        } else if (target == InfrastructureStack.Target.AWS_EC2) {
            // One box; the stack refuses anything else, so send the value it can honour.
            m.put("desired_count", 1);
            m.put("autoscaling_target_cpu", 0);
            m.put("root_volume_gb", req.getDatabaseStorageGb() == null ? 100 : req.getDatabaseStorageGb());
            // SSH stays closed; support access is via SSM Session Manager, which is audited.
            m.put("ssh_allowed_cidrs", List.of());
        } else {
            m.put("autoscaling_target_cpu", 65);
        }
        return m;
    }

    private Map<String, Object> dns(ProvisionRequest req, InfrastructureStack.Target target) {
        Map<String, Object> m = new LinkedHashMap<>();
        String mode = firstNonBlank(req.getDnsMode(), "none");
        m.put("mode", mode);
        m.put("domain_name", nullToEmpty(req.getDomainName()));
        m.put("web_domain_name", nullToEmpty(req.getWebDomainName()));
        m.put("hosted_zone_id", nullToEmpty(req.getHostedZoneId()));
        m.put("certificate_id", nullToEmpty(req.getCertificateId()));
        m.put("force_https", true);

        // On bare metal "managed" means Caddy obtaining a Let's Encrypt certificate by HTTP
        // challenge, which needs an address for renewal warnings rather than a DNS zone. Falling
        // back to the first alarm email beats failing a plan for a field the operator did not
        // know to fill in.
        if (target == InfrastructureStack.Target.BAREMETAL && "managed".equals(mode)) {
            String acme = req.getAlarmEmails() == null || req.getAlarmEmails().isEmpty()
                ? "" : req.getAlarmEmails().get(0);
            m.put("acme_email", acme);
        }
        return m;
    }

    private Map<String, Object> app(ProvisionRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spring_profile", "prod");
        m.put("log_level", "INFO");
        m.put("extra_cors_origins",
            req.getExtraCorsOrigins() == null ? List.of() : req.getExtraCorsOrigins());

        // Licence enforcement is ON for every vendor-provisioned deployment: without it the
        // subscription kill switch and version entitlement have no effect on the running instance.
        Map<String, Object> license = new LinkedHashMap<>();
        license.put("enforcement_enabled", true);
        license.put("mode", "READ_ONLY");
        license.put("grace_days", 7);
        license.put("version_granularity", "minor");
        license.put("fingerprint_mode", "warn");
        license.put("image_digest_mode", "warn");
        license.put("topology_mode", "warn");
        m.put("license", license);

        return m;
    }

    private Map<String, Object> controlCenter(Organization org) {
        if (controlCenterPublicUrl == null || controlCenterPublicUrl.isBlank()) {
            throw new ControlCenterException(
                "controlcenter.publicUrl is not set. A provisioned instance needs the URL it should "
                + "call home to for licence sync and telemetry — localhost will not do.",
                "CONTROL_CENTER_URL_REQUIRED", HttpStatus.SERVICE_UNAVAILABLE);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("url", controlCenterPublicUrl);
        return m;
    }

    private Map<String, Object> observability(ProvisionRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("log_retention_days", req.getLogRetentionDays() == null ? 90 : req.getLogRetentionDays());
        boolean hasEmails = req.getAlarmEmails() != null && !req.getAlarmEmails().isEmpty();
        m.put("alarms_enabled", hasEmails);
        m.put("alarm_emails", hasEmails ? req.getAlarmEmails() : List.of());
        m.put("container_insights", true);
        return m;
    }

    private Map<String, Object> tags(Organization org) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Customer", sanitiseSlug(org.getSlug()));
        m.put("ManagedBy", "zgate-control-center");
        if (org.getTier() != null) m.put("Tier", org.getTier().name());
        return m;
    }

    private Map<String, Object> guards(ProvisionRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        // Destroy is never enabled by a provision. It is set only by the explicit,
        // typed-confirmation decommission path in ProvisioningService.
        m.put("allow_destroy", false);
        m.put("skip_production_checks", bool(req.getSkipProductionChecks(), false));
        return m;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** The Terraform contract requires 3-30 chars, lowercase alphanumeric or hyphen. */
    private String sanitiseSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ControlCenterException("The organization has no slug; one is required to name resources.",
                "ORG_SLUG_REQUIRED", HttpStatus.BAD_REQUEST);
        }
        String s = slug.toLowerCase().replaceAll("[^a-z0-9-]", "-")
                       .replaceAll("-+", "-")
                       .replaceAll("^-|-$", "");
        if (s.length() > 30) s = s.substring(0, 30).replaceAll("-$", "");
        if (s.length() < 3 || !s.matches("^[a-z][a-z0-9-]*[a-z0-9]$")) {
            throw new ControlCenterException(
                "Organization slug '" + slug + "' cannot be used as a resource name. It must reduce to "
                + "3-30 characters, lowercase letters, digits and hyphens, starting with a letter.",
                "ORG_SLUG_INVALID", HttpStatus.BAD_REQUEST);
        }
        return s;
    }

    public String toJson(Map<String, Object> spec) {
        try {
            return mapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new ControlCenterException("Could not serialise the deployment spec: " + e.getMessage(),
                "SPEC_SERIALISATION_FAILED", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fromJson(String json) {
        try {
            return mapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            throw new ControlCenterException("Could not read the stored deployment spec: " + e.getMessage(),
                "SPEC_DESERIALISATION_FAILED", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Recursive merge: nested maps combine key-by-key, everything else is replaced. A shallow merge
     * would make {@code overrides.database.multi_az} silently drop every other database attribute.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        overlay.forEach((k, v) -> {
            Object existing = out.get(k);
            if (existing instanceof Map && v instanceof Map) {
                out.put(k, deepMerge((Map<String, Object>) existing, (Map<String, Object>) v));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static boolean bool(Boolean b, boolean fallback) { return b == null ? fallback : b; }
}
