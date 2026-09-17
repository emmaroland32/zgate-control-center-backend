package com.zgate.controlcenter.payload.request;

import com.zgate.controlcenter.domain.ControlCenterUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateUserRequest {
    @NotBlank @jakarta.validation.constraints.Size(max = 200) private String name;
    @Email @NotBlank @jakarta.validation.constraints.Size(max = 100) private String email;
    private String password;
    @NotNull private ControlCenterUser.Role role;
}
