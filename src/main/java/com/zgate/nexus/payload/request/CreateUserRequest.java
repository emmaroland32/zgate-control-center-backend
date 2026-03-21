package com.zgate.nexus.payload.request;

import com.zgate.nexus.domain.NexusUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateUserRequest {
    @NotBlank private String name;
    @Email @NotBlank private String email;
    private String password;
    @NotNull private NexusUser.Role role;
}
