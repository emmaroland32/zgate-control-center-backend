package com.zgate.controlcenter.payload.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class IssueLicenseRequest {
    @NotNull  private UUID organizationId;
    @NotBlank private String moduleName;
    /** Optional. When null, defaults to now + the org's licenseTtlDays (short-lived, auto-renewed). */
    private LocalDateTime expiresAt;
    private Integer maxUsers;
    private String features;
    private String fingerprint;
    /** Optional overrides; when null, maxVersion falls back to the org's entitledVersion. */
    private String maxVersion;
    private String imageDigest;
    private Integer graceDays;
}
