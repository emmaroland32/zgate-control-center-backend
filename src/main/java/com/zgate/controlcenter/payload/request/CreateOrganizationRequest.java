package com.zgate.controlcenter.payload.request;

import com.zgate.controlcenter.domain.Organization;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateOrganizationRequest {
    @NotBlank private String name;
    @NotBlank private String slug;
    @Email   private String contactEmail;
    private String contactName;
    private String country;
    private String region;
    @NotNull private Organization.Tier tier;
    @NotNull private Organization.DeploymentEnv deploymentEnv;
    private String backendUrl;
    private UUID partnerId;
}
