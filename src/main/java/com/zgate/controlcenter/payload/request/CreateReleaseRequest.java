package com.zgate.controlcenter.payload.request;

import com.zgate.controlcenter.domain.Release;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateReleaseRequest {
    @NotBlank private String version;
    @NotNull  private Release.Channel channel;
    @NotBlank private String dockerTag;
    private String dockerRegistry;
    private String releaseNotes;
    private boolean hasBreakingChanges;
    private boolean latest;
    private String migrations;
}
