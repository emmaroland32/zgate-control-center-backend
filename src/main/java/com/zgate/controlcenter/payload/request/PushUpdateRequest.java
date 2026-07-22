package com.zgate.controlcenter.payload.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class PushUpdateRequest {
    @NotEmpty private List<UUID> organizationIds;
    @NotNull  private UUID releaseId;
    private LocalDateTime scheduledAt;
    private boolean notifyContacts;
}
