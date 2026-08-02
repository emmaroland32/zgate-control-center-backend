package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A ZGATE deployment that Control Center provisioned into a customer's cloud, keyed on
 * (organization, environment, target) — the same triple the Terraform state object uses.
 *
 * <p><b>{@link #specJson} is replayed verbatim</b> on every later plan, apply, refresh and destroy,
 * including for a stack provisioned months earlier. That is what makes the Terraform contract in
 * {@code deploy/contract/variables.tf} append-only: removing or renaming a variable invalidates every
 * stored spec, and a spec that no longer parses is a stack that can no longer be updated OR torn down.
 *
 * <p>The spec holds no secret. Generated values live in the customer's own secret manager, and
 * customer-supplied ones are resolved from {@link CloudCredential} at run time. Control Center
 * deliberately never stores {@code ZGATE_FIELD_ENCRYPTION_KEY} — holding the key that decrypts every
 * customer's PII would make this database a far more valuable target than it needs to be.
 */
@Entity
@Table(name = "infrastructure_stacks",
       uniqueConstraints = @UniqueConstraint(name = "uq_infra_stack",
               columnNames = {"organization_id", "environment", "target"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InfrastructureStack {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 16)
    private String environment;   // dev | staging | prod

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Target target;

    private UUID cloudCredentialId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status;

    /** The rendered terraform.tfvars.json, minus secrets. See the class comment. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String specJson;

    /** {@code terraform output -json} from the last successful apply. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String outputsJson;

    // Denormalised from outputs so a list view needs no JSON parsing.
    @Column(length = 512) private String publicUrl;
    @Column(length = 512) private String webUrl;
    @Column(length = 64)  private String fingerprint;

    /**
     * The release version written into the spec — what the next (or last) apply runs. Distinct from
     * {@code Organization.deployedVersion}, which is what telemetry last observed actually running.
     */
    @Column(length = 40)
    private String releaseVersion;

    @Column(length = 255) private String stateBucket;
    @Column(length = 512) private String stateKey;

    private LocalDateTime lastPlanAt;
    private LocalDateTime lastAppliedAt;
    private LocalDateTime lastDriftCheckAt;

    /** Set by a refresh that finds reality no longer matches state — someone changed it by hand. */
    @Column(nullable = false)
    @Builder.Default
    private boolean driftDetected = false;

    @Column(columnDefinition = "TEXT")
    private String driftSummary;

    @Column(length = 120)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }

    public enum Target {
        AWS_ECS("aws-ecs"),
        AWS_EC2("aws-ec2"),
        AZURE_ACA("azure-aca"),
        GCP_CLOUDRUN("gcp-cloudrun"),
        /** A customer-supplied Ubuntu server reached over SSH. No cloud API involved. */
        BAREMETAL("baremetal");

        /** The stack directory name under {@code deploy/stacks/} — also the DB value. */
        private final String slug;

        Target(String slug) { this.slug = slug; }

        public String slug() { return slug; }

        public static Target fromSlug(String slug) {
            for (Target t : values()) {
                if (t.slug.equals(slug)) return t;
            }
            throw new IllegalArgumentException("Unknown deployment target: " + slug);
        }

        /** The cloud whose credentials this target needs. */
        public String provider() {
            return switch (this) {
                case AWS_ECS, AWS_EC2 -> "aws";
                case AZURE_ACA        -> "azure";
                case GCP_CLOUDRUN     -> "gcp";
                case BAREMETAL        -> "baremetal";
            };
        }
    }

    public enum Status {
        /** Spec captured, nothing provisioned yet. */
        DRAFT,
        PLANNING,
        /** A plan succeeded and is awaiting approval. */
        PLANNED,
        APPLYING,
        /** Live infrastructure, last apply succeeded. */
        ACTIVE,
        FAILED,
        DESTROYING,
        /** Torn down. The row is kept for the audit trail. */
        DESTROYED,
        /** Reality diverged from state — changed outside Control Center. */
        DRIFTED
    }

    /** True while a run is in flight; a second concurrent action must be refused. */
    public boolean isBusy() {
        return status == Status.PLANNING || status == Status.APPLYING || status == Status.DESTROYING;
    }
}
