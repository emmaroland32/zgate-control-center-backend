package com.zgate.controlcenter.payload.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @Email    @NotBlank private String email;
    @NotBlank private String password;
    /** 6-digit TOTP code — required once the operator has MFA enabled. */
    private String mfaCode;
}
