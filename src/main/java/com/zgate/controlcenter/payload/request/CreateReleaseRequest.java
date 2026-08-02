package com.zgate.controlcenter.payload.request;

import com.zgate.controlcenter.domain.Release;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateReleaseRequest {
    @NotBlank private String version;
    @NotNull  private Release.Channel channel;
    @NotBlank private String dockerTag;
    private String dockerRegistry;

    /** cosign digest of the backend image; blank = tag-only release (digest gate stays advisory). */
    @Pattern(regexp = "|sha256:[0-9a-f]{64}", message = "imageDigest must look like sha256:<64 hex chars>")
    private String imageDigest;

    @Pattern(regexp = "|sha256:[0-9a-f]{64}", message = "webImageDigest must look like sha256:<64 hex chars>")
    private String webImageDigest;

    private String releaseNotes;
    private boolean hasBreakingChanges;
    private boolean latest;
    private String migrations;
}
