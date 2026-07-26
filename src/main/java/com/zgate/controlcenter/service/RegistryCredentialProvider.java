package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Release;

import java.time.LocalDateTime;

/**
 * Mints a short-lived, pull-only registry credential for a specific entitled release. This is the
 * integration seam for the image-supply control: once {@link ImagePullTokenService} has decided an
 * org is entitled to a release, this hands back an actual credential the org's update agent can use
 * to {@code docker pull} it — and nothing else.
 *
 * <p>An implementation backed by AWS ECR (GetAuthorizationToken + a pull-only repository policy) or a
 * registry proxy is wired in per deployment. When no provider is configured, authorization still
 * happens (the org learns whether it may pull and the exact image ref) but no live credential is
 * issued — the org uses its standing registry access. Keeping the decision separate from the
 * credential means the commercial gate works even before the registry integration is in place.
 */
public interface RegistryCredentialProvider {

    /**
     * @return a short-lived pull credential for {@code release}, or {@code null} if credential
     *         minting is not configured in this deployment.
     */
    RegistryCredential mint(Release release);

    record RegistryCredential(String username, String password, LocalDateTime expiresAt) {}
}
