package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.CloudCredential;
import com.zgate.controlcenter.domain.InfrastructureStack;
import com.zgate.controlcenter.domain.ProvisioningRun;
import com.zgate.controlcenter.payload.request.ProvisionRequest;
import com.zgate.controlcenter.service.AuditService;
import com.zgate.controlcenter.service.provisioning.CloudCredentialService;
import com.zgate.controlcenter.service.provisioning.ProvisioningService;
import com.zgate.controlcenter.web.ResponseMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cloud provisioning: stand a full ZGATE deployment up in a customer's own cloud account.
 *
 * <p>Authorization is deliberately narrow. Every mutating endpoint is SUPER_ADMIN or ADMIN, and the
 * two genuinely destructive ones — storing a cloud credential and destroying a deployment — are
 * SUPER_ADMIN only. A support user can look, and nothing else.
 */
@RestController
@RequestMapping("/api/v1/provisioning")
@RequiredArgsConstructor
public class ProvisioningController {

    private final ProvisioningService service;
    private final CloudCredentialService credentials;
    private final com.zgate.controlcenter.service.provisioning.SshReachabilityService reachability;
    private final AuditService audit;
    private final com.zgate.controlcenter.security.ClientIpResolver clientIpResolver;

    // ── Readiness ───────────────────────────────────────────────────────────

    /** Whether provisioning can run, and precisely what is missing when it cannot. */
    @GetMapping("/readiness")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPPORT')")
    public ResponseEntity<Map<String, Object>> readiness() {
        return ResponseEntity.ok(service.readiness());
    }

    // ── Cloud credentials ───────────────────────────────────────────────────

    @GetMapping("/credentials/org/{orgId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPPORT')")
    public ResponseEntity<List<CloudCredential>> credentialsForOrg(@PathVariable UUID orgId) {
        return ResponseEntity.ok(credentials.findByOrg(orgId));
    }

    /**
     * Store a cloud credential.
     *
     * <p>SUPER_ADMIN only: for the static-key modes this accepts a long-lived key to a customer's
     * cloud account. Prefer {@code AWS_ASSUME_ROLE}, which stores no secret at all — the response
     * returns the generated external id for the customer's role trust policy.
     */
    @PostMapping("/credentials")
    @com.zgate.controlcenter.security.RequiresStepUp
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "CLOUD_CREDENTIAL_CREATED", value = "Cloud credential saved")
    public ResponseEntity<CloudCredential> createCredential(
            @RequestBody CloudCredentialService.CreateRequest req,
            @AuthenticationPrincipal UserDetails user,
            HttpServletRequest http) {

        CloudCredential saved = credentials.create(req, user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "CLOUD_CREDENTIAL_CREATED",
                  "CloudCredential", saved.getId().toString(), req.getOrganizationId(),
                  clientIp(http), "provider=" + saved.getProvider() + " mode=" + saved.getAuthMode(),
                  com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/credentials/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "CLOUD_CREDENTIAL_DELETED", value = "Cloud credential deleted")
    public ResponseEntity<Void> deleteCredential(@PathVariable UUID id,
                                                 @AuthenticationPrincipal UserDetails user,
                                                 HttpServletRequest http) {
        CloudCredential existing = credentials.findById(id);
        credentials.delete(id, user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "CLOUD_CREDENTIAL_DELETED",
                  "CloudCredential", id.toString(), existing.getOrganizationId(),
                  clientIp(http), null, com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/credentials/{id}/enabled")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "CLOUD_CREDENTIAL_UPDATED", value = "Cloud credential updated")
    public ResponseEntity<CloudCredential> setCredentialEnabled(@PathVariable UUID id,
                                                                @RequestBody EnabledUpdate body,
                                                                @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(credentials.setEnabled(id, body.isEnabled(), user.getUsername()));
    }

    /**
     * Probe a customer-supplied server before provisioning anything.
     *
     * <p>Strictly read-only — it connects, inspects and changes nothing — so it is safe to run
     * repeatedly while an operator and a customer sort out SSH access. ADMIN rather than
     * SUPER_ADMIN for exactly that reason.
     *
     * <p>Returns {@code {reachable, checks:[{name,status,detail}]}}, where every failing check
     * carries the concrete remedy rather than a status code.
     */
    @PostMapping("/credentials/{id}/ssh-check")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Map<String, Object>> sshCheck(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "4096") int minMemoryMb,
            @RequestParam(defaultValue = "40") int minDiskGb) {
        return ResponseEntity.ok(reachability.check(id, minMemoryMb, minDiskGb));
    }

    // ── Stacks ──────────────────────────────────────────────────────────────

    @GetMapping("/stacks")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPPORT')")
    public ResponseEntity<List<InfrastructureStack>> allStacks() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/stacks/org/{orgId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPPORT')")
    public ResponseEntity<List<InfrastructureStack>> stacksForOrg(@PathVariable UUID orgId) {
        return ResponseEntity.ok(service.findByOrg(orgId));
    }

    @GetMapping("/stacks/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPPORT')")
    public ResponseEntity<InfrastructureStack> stack(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findStack(id));
    }

    /**
     * Render a spec and run a PLAN. This creates nothing in the customer's cloud — an operator
     * reviews the plan and then calls apply.
     */
    @PostMapping("/provision")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "PROVISION_PLANNING",
                     value = "Planning the deployment. Review the plan before applying.")
    public ResponseEntity<InfrastructureStack> provision(@Valid @RequestBody ProvisionRequest req,
                                                         @AuthenticationPrincipal UserDetails user,
                                                         HttpServletRequest http) {
        InfrastructureStack stack = service.provision(req, user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "PROVISION_REQUESTED",
                  "InfrastructureStack", stack.getId().toString(), req.getOrganizationId(),
                  clientIp(http), "target=" + req.getTarget() + " env=" + req.getEnvironment(),
                  com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(stack);
    }

    /**
     * Upgrade a stack to a release: rewrites only the image block of the stored spec (every other
     * operator decision is preserved) and runs a PLAN. Entitlement-gated exactly like provisioning.
     */
    @PostMapping("/stacks/{id}/upgrade")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "STACK_UPGRADE_PLANNING",
                     value = "Planning the upgrade. Review the plan before applying.")
    public ResponseEntity<ProvisioningRun> upgrade(@PathVariable UUID id,
                                                   @RequestBody(required = false) UpgradeRequest body,
                                                   @AuthenticationPrincipal UserDetails user,
                                                   HttpServletRequest http) {
        InfrastructureStack stack = service.findStack(id);
        UUID releaseId = body == null ? null : body.getReleaseId();
        ProvisioningRun run = service.upgrade(id, releaseId, user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "STACK_UPGRADE_REQUESTED",
                  "InfrastructureStack", id.toString(), stack.getOrganizationId(),
                  clientIp(http), "runId=" + run.getId() + " release=" + service.findStack(id).getReleaseVersion(),
                  com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(run);
    }

    /** Apply the reviewed plan — this creates real infrastructure and starts billing the customer's cloud. */
    @PostMapping("/stacks/{id}/apply")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "PROVISION_APPLYING", value = "Applying — this takes several minutes")
    public ResponseEntity<ProvisioningRun> apply(@PathVariable UUID id,
                                                 @AuthenticationPrincipal UserDetails user,
                                                 HttpServletRequest http) {
        InfrastructureStack stack = service.findStack(id);
        ProvisioningRun run = service.apply(id, user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "PROVISION_APPLIED",
                  "InfrastructureStack", id.toString(), stack.getOrganizationId(),
                  clientIp(http), "runId=" + run.getId(),
                  com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(run);
    }

    /** Reconcile state with reality — reports anything changed outside Control Center. */
    @PostMapping("/stacks/{id}/refresh")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "PROVISION_REFRESHING", value = "Checking for drift")
    public ResponseEntity<ProvisioningRun> refresh(@PathVariable UUID id,
                                                   @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(service.refresh(id, user.getUsername()));
    }

    /**
     * Destroy a deployment.
     *
     * <p>SUPER_ADMIN only, and the caller must type the organization's slug back as confirmation.
     * Even then the stacks refuse to plan while {@code database.deletion_protection} is on, so
     * removing a customer's ledger takes two separate, deliberate changes.
     */
    @PostMapping("/stacks/{id}/destroy")
    @com.zgate.controlcenter.security.RequiresStepUp
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "PROVISION_DESTROYING", value = "Destroying the deployment")
    public ResponseEntity<ProvisioningRun> destroy(@PathVariable UUID id,
                                                   @Valid @RequestBody DestroyRequest body,
                                                   @AuthenticationPrincipal UserDetails user,
                                                   HttpServletRequest http) {
        InfrastructureStack stack = service.findStack(id);
        ProvisioningRun run = service.destroy(id, body.getConfirmation(), user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "PROVISION_DESTROYED",
                  "InfrastructureStack", id.toString(), stack.getOrganizationId(),
                  clientIp(http), "runId=" + run.getId() + " target=" + stack.getTarget().slug(),
                  com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(run);
    }

    // ── Runs ────────────────────────────────────────────────────────────────

    @GetMapping("/stacks/{id}/runs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPPORT')")
    public ResponseEntity<?> runs(@PathVariable UUID id,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.runsForStack(id, PageRequest.of(page, size)));
    }

    /**
     * A single run including its full terraform log. Polled by the UI while a run is in flight —
     * every secret in the configuration is declared sensitive, so Terraform redacts it in its own
     * output and the log is safe to display.
     */
    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPPORT')")
    public ResponseEntity<ProvisioningRun> run(@PathVariable UUID runId) {
        return ResponseEntity.ok(service.findRun(runId));
    }

    // ── Bodies ──────────────────────────────────────────────────────────────

    @Data
    public static class DestroyRequest {
        /** Must equal the organization's slug — proof the operator knows which deployment this is. */
        @NotBlank(message = "confirmation is required")
        private String confirmation;
    }

    @Data
    public static class EnabledUpdate {
        private boolean enabled;
    }

    @Data
    public static class UpgradeRequest {
        /** Release to upgrade to. Null means the org's latest ENTITLED release. */
        private UUID releaseId;
    }

    /** Honours X-Forwarded-For only from a configured proxy hop — see ClientIpResolver. */
    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }
}
