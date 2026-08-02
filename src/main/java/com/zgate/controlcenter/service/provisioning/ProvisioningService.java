package com.zgate.controlcenter.service.provisioning;

import com.zgate.controlcenter.domain.*;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.ProvisionRequest;
import com.zgate.controlcenter.repository.*;
import com.zgate.controlcenter.service.LicenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Orchestrates provisioning a ZGATE deployment into a customer's cloud.
 *
 * <p>The flow an operator sees:
 * <pre>
 *   provision()  ->  renders a spec, saves the stack, runs a PLAN
 *   apply()      ->  applies the reviewed plan, live infrastructure appears
 *   refresh()    ->  drift check
 *   destroy()    ->  tears it down, behind two independent confirmations
 * </pre>
 *
 * <p>Every action is recorded as a {@link ProvisioningRun}, which is append-only: this is the audit
 * trail for infrastructure changes to a regulated financial system.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProvisioningService {

    private final InfrastructureStackRepository stackRepo;
    private final ProvisioningRunRepository runRepo;
    private final OrganizationRepository orgRepo;
    private final ReleaseRepository releaseRepo;
    private final CloudCredentialService credentialService;
    private final SpecRenderer specRenderer;
    private final TerraformRunner runner;
    private final ProvisioningExecutor executor;

    /**
     * Optional. Only bare metal needs it — every other target mirrors the image with the runner's
     * own vendor credentials rather than handing one to the customer's machine.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zgate.controlcenter.service.RegistryCredentialProvider registryCredentialProvider;

    // ── State backend (vendor-owned) ────────────────────────────────────────
    @Value("${controlcenter.provisioning.state.bucket:}")
    private String stateBucket;

    @Value("${controlcenter.provisioning.state.region:eu-west-2}")
    private String stateRegion;

    @Value("${controlcenter.provisioning.state.kmsKeyId:}")
    private String stateKmsKeyId;

    @Value("${controlcenter.provisioning.state.lockTable:}")
    private String stateLockTable;

    @Value("${controlcenter.provisioning.state.accessKeyId:}")
    private String stateAccessKeyId;

    @Value("${controlcenter.provisioning.state.secretAccessKey:}")
    private String stateSecretAccessKey;

    // ── Vendor source registry (for image mirroring) ────────────────────────
    @Value("${controlcenter.provisioning.sourceRegistry.accessKeyId:}")
    private String srcRegistryAccessKeyId;

    @Value("${controlcenter.provisioning.sourceRegistry.secretAccessKey:}")
    private String srcRegistrySecretAccessKey;

    @Value("${controlcenter.provisioning.sourceRegistry.region:eu-west-2}")
    private String srcRegistryRegion;

    @Value("${controlcenter.provisioning.timeoutSeconds:3600}")
    private int timeoutSeconds;

    // ── Reads ───────────────────────────────────────────────────────────────

    public List<InfrastructureStack> findByOrg(UUID orgId) {
        return stackRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    public List<InfrastructureStack> findAll() {
        return stackRepo.findAll();
    }

    public InfrastructureStack findStack(UUID id) {
        return stackRepo.findById(id).orElseThrow(() -> new ControlCenterException(
            "Infrastructure stack not found: " + id, "STACK_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    public Page<ProvisioningRun> runsForStack(UUID stackId, Pageable pageable) {
        return runRepo.findByStackIdOrderByCreatedAtDesc(stackId, pageable);
    }

    public ProvisioningRun findRun(UUID runId) {
        return runRepo.findById(runId).orElseThrow(() -> new ControlCenterException(
            "Provisioning run not found: " + runId, "PROVISIONING_RUN_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    /** Whether provisioning can run at all, and why not when it cannot. */
    public Map<String, Object> readiness() {
        Map<String, Object> m = new LinkedHashMap<>();
        String reason = runner.unavailableReason();
        boolean stateOk = stateBucket != null && !stateBucket.isBlank();
        m.put("available", reason == null && stateOk);
        m.put("runnerReason", reason);
        m.put("stateConfigured", stateOk);
        if (!stateOk) {
            m.put("stateReason", "controlcenter.provisioning.state.bucket is not set. "
                + "Terraform state holds generated database passwords and must live in an encrypted, "
                + "versioned, vendor-owned S3 bucket.");
        }
        return m;
    }

    // ── Provision ───────────────────────────────────────────────────────────

    /**
     * Create or update a stack from a request and immediately run a PLAN.
     *
     * <p>Deliberately does NOT apply. An operator reviews what will be created — the resource counts
     * and the plan output — and then calls {@link #apply}. Provisioning a customer's production
     * financial system is not a one-click action.
     */
    @Transactional
    public InfrastructureStack provision(ProvisionRequest req, String actor) {
        requireReady();

        Organization org = orgRepo.findById(req.getOrganizationId()).orElseThrow(() ->
            new ControlCenterException("Organization not found: " + req.getOrganizationId(),
                "ORGANIZATION_NOT_FOUND", HttpStatus.NOT_FOUND));

        InfrastructureStack.Target target = InfrastructureStack.Target.fromSlug(req.getTarget());
        String environment = req.getEnvironment() == null ? "prod" : req.getEnvironment();

        CloudCredential credential = credentialService.loadForProvisioning(req.getCloudCredentialId());
        if (!credential.getOrganizationId().equals(org.getId())) {
            // A credential belongs to one organization. Using another org's credential would deploy
            // one customer's system into a different customer's cloud account.
            throw new ControlCenterException(
                "That cloud credential belongs to a different organization.",
                "CREDENTIAL_ORG_MISMATCH", HttpStatus.FORBIDDEN);
        }

        Release release = resolveEntitledRelease(org, req.getReleaseId());

        InfrastructureStack stack = stackRepo
            .findByOrganizationIdAndEnvironmentAndTarget(org.getId(), environment, target)
            .orElseGet(() -> InfrastructureStack.builder()
                .organizationId(org.getId())
                .environment(environment)
                .target(target)
                .status(InfrastructureStack.Status.DRAFT)
                .specJson("{}")
                .createdBy(actor)
                .build());

        if (stack.isBusy()) {
            throw new ControlCenterException(
                "This stack already has a " + stack.getStatus() + " run in flight. Wait for it to finish.",
                "STACK_BUSY", HttpStatus.CONFLICT);
        }

        Map<String, Object> spec = specRenderer.render(req, org, release, credential,
            stack.getId() == null ? UUID.randomUUID() : stack.getId());

        stack.setCloudCredentialId(credential.getId());
        stack.setSpecJson(specRenderer.toJson(spec));
        stack.setReleaseVersion(release.getVersion());
        stack.setStateBucket(stateBucket);
        stack.setStateKey(stateKey(org.getId(), environment, target));
        stack.setStatus(InfrastructureStack.Status.PLANNING);
        stack = stackRepo.save(stack);

        log.info("Provisioning requested: org={} target={} env={} release={} by={}",
                 org.getSlug(), target.slug(), environment, release.getVersion(), actor);

        startRun(stack, ProvisioningRun.Action.PLAN, req, actor,
                 InfrastructureStack.Status.PLANNED, false);

        return stack;
    }

    /**
     * Move an existing stack to a new release by rewriting ONLY the {@code image} block of its
     * stored spec, then running a PLAN.
     *
     * <p>This is deliberately not a re-provision: every other operator decision in the spec — DNS
     * mode, sizing, multi-AZ, backups, alarm emails, overrides — is preserved verbatim. A full
     * {@link #provision} replaces the spec wholesale, which is the right tool for reshaping a
     * deployment but the wrong one for "same deployment, newer version".
     *
     * <p>Runs the same entitlement gate as provisioning: a lapsed subscription or a release beyond
     * the org's entitled version is refused with a 402 before anything is written.
     */
    @Transactional
    public ProvisioningRun upgrade(UUID stackId, UUID releaseId, String actor) {
        requireReady();
        InfrastructureStack stack = findStack(stackId);

        if (stack.isBusy()) {
            throw new ControlCenterException(
                "This stack already has a " + stack.getStatus() + " run in flight.",
                "STACK_BUSY", HttpStatus.CONFLICT);
        }
        if (stack.getStatus() == InfrastructureStack.Status.DESTROYED) {
            throw new ControlCenterException(
                "This stack was destroyed. Provision it again to recreate it.",
                "STACK_DESTROYED", HttpStatus.CONFLICT);
        }
        if (stack.getLastAppliedAt() == null) {
            throw new ControlCenterException(
                "This stack has never been applied — there is nothing to upgrade. Provision it instead.",
                "STACK_NEVER_APPLIED", HttpStatus.CONFLICT);
        }

        Organization org = orgRepo.findById(stack.getOrganizationId()).orElseThrow(() ->
            new ControlCenterException("Organization not found", "ORGANIZATION_NOT_FOUND", HttpStatus.NOT_FOUND));

        Release release = resolveEntitledRelease(org, releaseId);

        if (release.getVersion().equals(stack.getReleaseVersion())) {
            throw new ControlCenterException(
                "This stack is already on release " + release.getVersion() + ".",
                "STACK_ALREADY_ON_RELEASE", HttpStatus.CONFLICT);
        }

        Map<String, Object> spec = specRenderer.fromJson(stack.getSpecJson());
        Map<String, Object> merged = specRenderer.deepMerge(spec,
            Map.of("image", specRenderer.imageBlock(stack.getTarget(), release)));

        stack.setSpecJson(specRenderer.toJson(merged));
        stack.setReleaseVersion(release.getVersion());
        stack.setStatus(InfrastructureStack.Status.PLANNING);
        stackRepo.save(stack);

        log.info("Upgrade requested: org={} stack={} ({} {}) -> release {} by={}",
                 org.getSlug(), stack.getId(), stack.getTarget().slug(), stack.getEnvironment(),
                 release.getVersion(), actor);

        return startRun(stack, ProvisioningRun.Action.PLAN, null, actor,
                        InfrastructureStack.Status.PLANNED, false);
    }

    /** Apply the stack's stored spec — the reviewed plan becomes real infrastructure. */
    @Transactional
    public ProvisioningRun apply(UUID stackId, String actor) {
        requireReady();
        InfrastructureStack stack = findStack(stackId);

        if (stack.isBusy()) {
            throw new ControlCenterException(
                "This stack already has a " + stack.getStatus() + " run in flight.",
                "STACK_BUSY", HttpStatus.CONFLICT);
        }
        if (stack.getStatus() == InfrastructureStack.Status.DESTROYED) {
            throw new ControlCenterException(
                "This stack was destroyed. Provision it again to recreate it.",
                "STACK_DESTROYED", HttpStatus.CONFLICT);
        }

        stack.setStatus(InfrastructureStack.Status.APPLYING);
        stackRepo.save(stack);

        return startRun(stack, ProvisioningRun.Action.APPLY, null, actor,
                        InfrastructureStack.Status.ACTIVE, false);
    }

    /** Reconcile state with reality without changing anything — the drift check. */
    @Transactional
    public ProvisioningRun refresh(UUID stackId, String actor) {
        requireReady();
        InfrastructureStack stack = findStack(stackId);

        if (stack.isBusy()) {
            throw new ControlCenterException("This stack has a run in flight.", "STACK_BUSY", HttpStatus.CONFLICT);
        }
        if (stack.getLastAppliedAt() == null) {
            throw new ControlCenterException(
                "This stack has never been applied, so there is nothing to compare against.",
                "STACK_NEVER_APPLIED", HttpStatus.CONFLICT);
        }

        return startRun(stack, ProvisioningRun.Action.REFRESH, null, actor,
                        InfrastructureStack.Status.ACTIVE, false);
    }

    /**
     * Tear a deployment down.
     *
     * <p>Behind THREE independent gates, because this deletes a customer's system of record:
     * <ol>
     *   <li>the caller must type the organization slug back, proving they know which one this is</li>
     *   <li>the spec is rewritten with {@code guards.allow_destroy = true}, which the Terraform
     *       preconditions require</li>
     *   <li>the runner demands {@code ZGATE_CONFIRM_DESTROY} in its environment</li>
     * </ol>
     * Even then, {@code database.deletion_protection} must have been turned off in a SEPARATE earlier
     * change — the stacks refuse to plan a destroy while it is on.
     */
    @Transactional
    public ProvisioningRun destroy(UUID stackId, String confirmation, String actor) {
        requireReady();
        InfrastructureStack stack = findStack(stackId);

        Organization org = orgRepo.findById(stack.getOrganizationId()).orElseThrow(() ->
            new ControlCenterException("Organization not found", "ORGANIZATION_NOT_FOUND", HttpStatus.NOT_FOUND));

        if (!org.getSlug().equals(confirmation)) {
            throw new ControlCenterException(
                "To destroy this deployment, confirm with the organization slug: '" + org.getSlug() + "'.",
                "DESTROY_CONFIRMATION_REQUIRED", HttpStatus.BAD_REQUEST);
        }
        if (stack.isBusy()) {
            throw new ControlCenterException("This stack has a run in flight.", "STACK_BUSY", HttpStatus.CONFLICT);
        }

        // Flip the guard in the stored spec. The Terraform preconditions read this, so a destroy
        // cannot proceed on a spec that was never explicitly marked destroyable.
        Map<String, Object> spec = specRenderer.fromJson(stack.getSpecJson());
        Map<String, Object> merged = specRenderer.deepMerge(spec,
            Map.of("guards", Map.of("allow_destroy", true)));
        stack.setSpecJson(specRenderer.toJson(merged));
        stack.setStatus(InfrastructureStack.Status.DESTROYING);
        stackRepo.save(stack);

        log.warn("DESTROY requested for org {} stack {} ({}) by {}",
                 org.getSlug(), stackId, stack.getTarget().slug(), actor);

        return startRun(stack, ProvisioningRun.Action.DESTROY, null, actor,
                        InfrastructureStack.Status.DESTROYED, true);
    }

    // ── Run plumbing ────────────────────────────────────────────────────────

    private ProvisioningRun startRun(InfrastructureStack stack,
                                     ProvisioningRun.Action action,
                                     ProvisionRequest req,
                                     String actor,
                                     InfrastructureStack.Status successStatus,
                                     boolean confirmDestroy) {

        ProvisioningRun run = runRepo.save(ProvisioningRun.builder()
            .stackId(stack.getId())
            .organizationId(stack.getOrganizationId())
            .action(action)
            .status(ProvisioningRun.Status.QUEUED)
            .triggeredBy(actor)
            .build());

        // Customer-supplied credentials (an external database password, an external Redis password)
        // are merged into the spec HERE, for this run only. They are never written to
        // infrastructure_stacks.spec_json.
        String specForRun = withRuntimeSecrets(stack, req);

        TerraformRunner.Request request = new TerraformRunner.Request(
            stack.getOrganizationId(),
            stack.getEnvironment(),
            stack.getTarget().slug(),
            action,
            specForRun,
            buildEnvironment(stack),
            confirmDestroy,
            timeoutSeconds);

        executor.executeAsync(run.getId(), request, successStatus);
        return run;
    }

    /**
     * Merge run-time-only secrets into the spec.
     *
     * <p>Currently the credentials for a customer-supplied database or cache. These arrive on the
     * provision request, are used for this run, and are deliberately not persisted — the stack copies
     * them into the customer's own secret manager on the first apply, and every later run reads them
     * from there rather than from Control Center.
     */
    private String withRuntimeSecrets(InfrastructureStack stack, ProvisionRequest req) {
        Map<String, Object> secrets = new LinkedHashMap<>();

        // Bare metal needs its SSH key on EVERY run, not just the first: Terraform's connection
        // block reads it as a variable, so a later apply, refresh or destroy cannot reach the
        // server without it. Unlike a database password, it is never copied to the box.
        if (stack.getTarget() == InfrastructureStack.Target.BAREMETAL && stack.getCloudCredentialId() != null) {
            secrets.put("ssh_private_key", credentialService.sshPrivateKey(stack.getCloudCredentialId()));
            // The bare-metal server pulls the image directly; mint a short-lived registry
            // credential for this run so the bootstrap can authenticate.
            if (!isBlank(srcRegistryAccessKeyId)) {
                Map<String, String> reg = registryCredential();
                if (reg != null) {
                    secrets.put("registry_username", reg.get("username"));
                    secrets.put("registry_password", reg.get("password"));
                }
            }
        }

        if (req == null) {
            return secrets.isEmpty() ? stack.getSpecJson() : merged(stack, secrets);
        }
        if (req.getExternalDatabase() != null && req.getExternalDatabase().getPassword() != null
                && !req.getExternalDatabase().getPassword().isBlank()) {
            secrets.put("external_db_password", req.getExternalDatabase().getPassword());
        }
        if (req.getExternalCache() != null && req.getExternalCache().getPassword() != null
                && !req.getExternalCache().getPassword().isBlank()) {
            secrets.put("external_redis_password", req.getExternalCache().getPassword());
        }
        if (secrets.isEmpty()) return stack.getSpecJson();
        return merged(stack, secrets);
    }

    private String merged(InfrastructureStack stack, Map<String, Object> secrets) {
        Map<String, Object> spec = specRenderer.fromJson(stack.getSpecJson());
        return specRenderer.toJson(specRenderer.deepMerge(spec, Map.of("secrets", secrets)));
    }

    /**
     * A registry credential the customer's own server can pull with.
     *
     * <p>Delegated to the {@link com.zgate.controlcenter.service.RegistryCredentialProvider} when one
     * is configured, so an ECR deployment mints a real short-lived token rather than shipping a
     * standing key to a machine we do not control.
     */
    private Map<String, String> registryCredential() {
        if (registryCredentialProvider == null) return null;
        try {
            // The release argument is advisory: a registry token is scoped to the registry, not to
            // one image, and the shipped ECR provider ignores it. Entitlement to a specific release
            // is already enforced in resolveEntitledRelease before we ever get here.
            var cred = registryCredentialProvider.mint(null);
            if (cred == null) return null;
            return Map.of("username", cred.username(), "password", cred.password());
        } catch (RuntimeException e) {
            log.warn("Could not mint a registry credential for a bare-metal deployment: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Assemble the provisioning container's environment: the customer's cloud credentials, the
     * VENDOR's state-bucket credentials, and the VENDOR's source-registry credentials.
     *
     * <p>Three distinct identities in one map, which is the point — the customer's credential can
     * only touch the customer's cloud, and neither of the vendor's can.
     */
    private Map<String, String> buildEnvironment(InfrastructureStack stack) {
        Map<String, String> env = new LinkedHashMap<>();

        if (stack.getCloudCredentialId() != null) {
            env.putAll(credentialService.toRunnerEnvironment(stack.getCloudCredentialId()));
        }

        env.put("TF_STATE_BUCKET", stateBucket);
        env.put("TF_STATE_REGION", stateRegion);
        if (!isBlank(stateKmsKeyId))  env.put("TF_STATE_KMS_KEY_ID", stateKmsKeyId);
        if (!isBlank(stateLockTable)) env.put("TF_STATE_LOCK_TABLE", stateLockTable);
        if (!isBlank(stateAccessKeyId)) {
            env.put("AWS_STATE_ACCESS_KEY_ID", stateAccessKeyId);
            env.put("AWS_STATE_SECRET_ACCESS_KEY", stateSecretAccessKey);
        }

        // Read access to the vendor's release registry, so the runner can mirror the image into the
        // customer's cloud. Pull-only credentials — this must never be able to push.
        if (!isBlank(srcRegistryAccessKeyId)) {
            env.put("ZGATE_SRC_AWS_ACCESS_KEY_ID", srcRegistryAccessKeyId);
            env.put("ZGATE_SRC_AWS_SECRET_ACCESS_KEY", srcRegistrySecretAccessKey);
            env.put("ZGATE_SRC_AWS_REGION", srcRegistryRegion);
        }

        return env;
    }

    /**
     * Resolve which release to deploy, refusing anything beyond what the org has paid for.
     *
     * <p>Reuses {@link LicenseService#isEntitled} so provisioning honours exactly the same
     * subscription gate as the image-pull endpoint — a lapsed customer cannot be handed a fresh
     * deployment of a version they are not entitled to.
     */
    private Release resolveEntitledRelease(Organization org, UUID releaseId) {
        if (!LicenseService.isEntitled(org, LocalDateTime.now())) {
            throw new ControlCenterException(
                "This organization's subscription has lapsed, so a new deployment cannot be provisioned.",
                "SUBSCRIPTION_LAPSED", HttpStatus.PAYMENT_REQUIRED);
        }

        Release release = releaseId != null
            ? releaseRepo.findById(releaseId).orElseThrow(() -> new ControlCenterException(
                "Release not found: " + releaseId, "RELEASE_NOT_FOUND", HttpStatus.NOT_FOUND))
            : releaseRepo.findByIsLatestTrue().orElseThrow(() -> new ControlCenterException(
                "No release is marked latest, and none was specified.",
                "NO_RELEASE_AVAILABLE", HttpStatus.CONFLICT));

        if (release.getApprovalStatus() != Release.ApprovalStatus.APPROVED) {
            throw new ControlCenterException(
                "Release " + release.getVersion() + " is not approved for distribution.",
                "RELEASE_NOT_APPROVED", HttpStatus.CONFLICT);
        }

        if (org.getEntitledVersion() != null && !org.getEntitledVersion().isBlank()
                && com.zgate.controlcenter.service.AnomalyDetectionService
                       .compareVersions(release.getVersion(), org.getEntitledVersion(), 2) > 0) {
            throw new ControlCenterException(
                "Release " + release.getVersion() + " is beyond this organization's entitlement ("
                + org.getEntitledVersion() + ").",
                "VERSION_NOT_ENTITLED", HttpStatus.PAYMENT_REQUIRED);
        }

        return release;
    }

    private String stateKey(UUID orgId, String environment, InfrastructureStack.Target target) {
        return "org/" + orgId + "/" + environment + "/" + target.slug() + "/terraform.tfstate";
    }

    private void requireReady() {
        String reason = runner.unavailableReason();
        if (reason != null) {
            throw new ControlCenterException(reason, "PROVISIONING_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (isBlank(stateBucket)) {
            throw new ControlCenterException(
                "controlcenter.provisioning.state.bucket is not set. Terraform state holds generated "
                + "database passwords and the field-encryption key, so it must live in an encrypted, "
                + "versioned, vendor-owned S3 bucket before any provisioning can run.",
                "PROVISIONING_STATE_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
