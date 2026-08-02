package com.zgate.controlcenter.payload.request;

import com.zgate.controlcenter.domain.Organization;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sets an organization's commercial entitlements — the levers behind the licensing controls. Full
 * replace of the entitlement block: a {@code null} field clears that entitlement (returns it to
 * unmanaged). All fields are back-office-set by an admin.
 */
@Data
public class UpdateEntitlementRequest {

    /** Paid-through date; drives short-lived-license renewal (the kill switch). Null = perpetual. */
    private LocalDateTime subscriptionValidUntil;

    /** Highest ZGATE version the org may run (stamped as license maxVersion). Null = no version cap. */
    private String entitledVersion;

    /** Lifetime in days of each issued/renewed license. Null = server default. */
    private Integer licenseTtlDays;

    /** Entitled number of concurrent environments. Null = no concurrent-use check. */
    private Integer maxInstances;

    /** Licensed deployment topology (SINGLE_NODE | HIGH_AVAILABILITY | MULTI_REGION). Null = no tier check. */
    private Organization.DeploymentTier deploymentTier;

    /** Monthly platform-subscription price — drives automatic renewal invoicing. Null = billed out of band. */
    private java.math.BigDecimal subscriptionMonthlyFee;

    /**
     * Contractual change window: orchestrator-initiated applies run only inside it (org-local
     * time; start after end wraps midnight). All three null = no restriction.
     */
    private java.time.LocalTime maintenanceWindowStart;
    private java.time.LocalTime maintenanceWindowEnd;
    private String maintenanceTimezone;
}
