package com.zgate.nexus.payload.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class IssueLicenseRequest {
    @NotNull  private UUID organizationId;
    @NotBlank private String moduleName;
    private LocalDateTime expiresAt;
    private Integer maxUsers;
    private String features;
    private String fingerprint;
}
