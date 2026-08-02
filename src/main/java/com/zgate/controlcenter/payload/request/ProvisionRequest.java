package com.zgate.controlcenter.payload.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an operator fills in to deploy ZGATE into a customer's cloud.
 *
 * <p>This is deliberately SMALLER than the Terraform contract in
 * {@code deploy/contract/variables.tf}: it carries the decisions a person actually makes, and
 * {@code SpecRenderer} fills in everything else from the organization record, the entitled release
 * and the stored credential. An operator should not be asked for a task CPU value or a KMS key arn
 * to stand up a normal deployment.
 *
 * <p>Anything the contract accepts but this does not can still be reached through {@link #overrides},
 * which is merged over the rendered spec. That is the escape hatch for the unusual deal, not the
 * normal path — nothing in it is validated here.
 */
@Data
public class ProvisionRequest {

    @NotNull(message = "organizationId is required")
    private UUID organizationId;

    /** aws-ecs | aws-ec2 | azure-aca | gcp-cloudrun | baremetal */
    @NotBlank(message = "target is required")
    @Pattern(regexp = "aws-ecs|aws-ec2|azure-aca|gcp-cloudrun|baremetal",
             message = "target must be one of: aws-ecs, aws-ec2, azure-aca, gcp-cloudrun, baremetal")
    private String target;

    @Pattern(regexp = "dev|staging|prod", message = "environment must be dev, staging or prod")
    private String environment = "prod";

    /** Which stored cloud credential to provision with. Must match the target's provider. */
    @NotNull(message = "cloudCredentialId is required")
    private UUID cloudCredentialId;

    /** Provider region. Falls back to the credential's default when blank. */
    private String region;

    /** Release to deploy. Null means the org's latest ENTITLED release, never simply the latest. */
    private UUID releaseId;

    // ── Sizing ──────────────────────────────────────────────────────────────
    @Pattern(regexp = "small|medium|large", message = "size must be small, medium or large")
    private String size = "medium";

    // ── Database ────────────────────────────────────────────────────────────
    /** managed = the stack provisions it; external = connect to one the customer already runs. */
    @Pattern(regexp = "managed|external", message = "databaseMode must be managed or external")
    private String databaseMode = "managed";

    private ExternalDatabase externalDatabase;

    @Min(value = 20, message = "databaseStorageGb must be at least 20")
    @Max(value = 16384, message = "databaseStorageGb must be at most 16384")
    private Integer databaseStorageGb = 100;

    private Boolean databaseMultiAz = Boolean.FALSE;

    @Min(value = 1, message = "databaseBackupRetentionDays must be at least 1")
    @Max(value = 35, message = "databaseBackupRetentionDays must be at most 35")
    private Integer databaseBackupRetentionDays = 14;

    // ── Cache ───────────────────────────────────────────────────────────────
    /** managed | external | none. `none` is only viable at a single replica. */
    @Pattern(regexp = "managed|external|none", message = "cacheMode must be managed, external or none")
    private String cacheMode = "managed";

    private ExternalCache externalCache;

    // ── Network ─────────────────────────────────────────────────────────────
    /** create = greenfield network; existing = deploy into the customer's own. */
    @Pattern(regexp = "create|existing", message = "networkMode must be create or existing")
    private String networkMode = "create";

    private ExistingNetwork existingNetwork;

    /** Greenfield CIDR. Must not collide with anything the customer already peers with. */
    private String cidrBlock = "10.42.0.0/16";

    /** false puts the load balancer on the private network — reachable only over their VPN. */
    private Boolean publicIngress = Boolean.TRUE;

    /** Who may reach the load balancer. Defaults to open; narrow it for a private deployment. */
    private List<String> allowedIngressCidrs;

    // ── DNS / TLS ───────────────────────────────────────────────────────────
    /** none | managed | existing. `none` is refused for a production AWS stack (no TLS). */
    @Pattern(regexp = "none|managed|existing", message = "dnsMode must be none, managed or existing")
    private String dnsMode = "none";

    private String domainName;
    private String webDomainName;
    private String hostedZoneId;
    private String certificateId;

    // ── Options ─────────────────────────────────────────────────────────────
    /** Deploy the Next.js frontend alongside the API. */
    private Boolean deployWeb = Boolean.TRUE;

    /** Wire the managed-backup agent. Required for a production single-node stack. */
    private Boolean backupEnabled = Boolean.FALSE;

    private List<String> alarmEmails;

    @Min(value = 30, message = "logRetentionDays must be at least 30")
    private Integer logRetentionDays = 90;

    /** Extra browser origins beyond the ones derived from the domains above. */
    private List<String> extraCorsOrigins;

    /**
     * Raw contract overrides, merged over the rendered spec as a deep merge. Use for anything the
     * fields above do not model. Unvalidated by design — a wrong value here fails at plan time.
     */
    private Map<String, Object> overrides;

    /**
     * Bypass the production preflight checks (TLS required, backups retained, deletion protection).
     * Only honoured for a non-production environment or an explicitly acknowledged pilot.
     */
    private Boolean skipProductionChecks = Boolean.FALSE;

    @Data
    public static class ExternalDatabase {
        @NotBlank private String host;
        private Integer port = 5432;
        @NotBlank private String name;
        @NotBlank private String username;
        /** Held only for the duration of the request; stored encrypted, never in the spec. */
        private String password;
        private String sslMode = "require";
    }

    @Data
    public static class ExternalCache {
        @NotBlank private String host;
        private Integer port = 6379;
        private String password;
        private Boolean tls = Boolean.TRUE;
    }

    @Data
    public static class ExistingNetwork {
        /** AWS vpc-… / Azure VNet resource id / GCP network self-link. */
        @NotBlank private String vpcId;
        private List<String> publicSubnetIds;
        @NotEmpty(message = "at least one private subnet is required")
        private List<String> privateSubnetIds;
    }
}
