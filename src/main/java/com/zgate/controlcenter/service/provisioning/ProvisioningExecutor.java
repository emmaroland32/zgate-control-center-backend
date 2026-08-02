package com.zgate.controlcenter.service.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.domain.InfrastructureStack;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.domain.ProvisioningRun;
import com.zgate.controlcenter.repository.InfrastructureStackRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.ProvisioningRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a provisioning action in the background and records the outcome.
 *
 * <p>This is a SEPARATE BEAN from {@link ProvisioningService} on purpose. {@code @Async} is proxy-based,
 * so a method called from inside the same class runs on the caller's thread — the annotation is
 * silently ignored. An apply takes minutes; running it inline would hold an HTTP worker (and a
 * transaction) open for the duration. Keeping the async entry point on an injected bean is what makes
 * the annotation actually take effect.
 *
 * <p>Every database write here is in its own short transaction. The terraform run itself is NOT in a
 * transaction: holding one open across a ten-minute apply would pin a connection and, worse, roll
 * back the run record if anything later failed — losing the audit trail of infrastructure that was
 * genuinely created.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProvisioningExecutor {

    private final TerraformRunner runner;
    private final InfrastructureStackRepository stackRepo;
    private final ProvisioningRunRepository runRepo;
    private final OrganizationRepository orgRepo;
    private final ObjectMapper mapper;

    /** Cap on stored log size. A runaway apply must not put a 200MB string in a TEXT column. */
    private static final int MAX_LOG_CHARS = 512_000;

    @Async
    public void executeAsync(UUID runId,
                             TerraformRunner.Request request,
                             InfrastructureStack.Status successStatus) {
        execute(runId, request, successStatus);
    }

    /** Synchronous form — used by the scheduled drift check, which is already off the request thread. */
    public void execute(UUID runId,
                        TerraformRunner.Request request,
                        InfrastructureStack.Status successStatus) {

        markRunning(runId);

        StringBuilder liveLog = new StringBuilder();
        TerraformRunner.Result result;
        try {
            result = runner.run(request, line -> {
                if (liveLog.length() < MAX_LOG_CHARS) liveLog.append(line).append('\n');
            });
        } catch (RuntimeException e) {
            // A crash in the runner must still close out the run record, or the stack is stuck
            // in APPLYING forever and every later action is refused as "busy".
            log.error("Provisioning run {} threw: {}", runId, e.toString());
            finishFailed(runId, liveLog.toString(), "The provisioning runner failed: " + e.getMessage());
            return;
        }

        if (result.success()) {
            finishSucceeded(runId, request, result, successStatus);
        } else {
            finishFailed(runId, result.log(), result.errorMessage());
        }
    }

    // ── Run lifecycle ───────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markRunning(UUID runId) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setStatus(ProvisioningRun.Status.RUNNING);
            r.setStartedAt(LocalDateTime.now());
            runRepo.save(r);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void finishSucceeded(UUID runId,
                                   TerraformRunner.Request request,
                                   TerraformRunner.Result result,
                                   InfrastructureStack.Status successStatus) {

        ProvisioningRun run = runRepo.findById(runId).orElse(null);
        if (run == null) {
            log.error("Provisioning run {} vanished before it could be closed out", runId);
            return;
        }

        run.setStatus(ProvisioningRun.Status.SUCCESS);
        run.setCompletedAt(LocalDateTime.now());
        run.setExitCode(result.exitCode());
        run.setLog(truncate(result.log()));
        run.setPlanJson(result.planJson());
        run.setResourcesToAdd(nonNegative(result.resourcesToAdd()));
        run.setResourcesToChange(nonNegative(result.resourcesToChange()));
        run.setResourcesToDestroy(nonNegative(result.resourcesToDestroy()));
        runRepo.save(run);

        stackRepo.findById(run.getStackId()).ifPresent(stack -> {
            stack.setStatus(successStatus);

            switch (run.getAction()) {
                case PLAN -> stack.setLastPlanAt(LocalDateTime.now());
                case APPLY -> {
                    stack.setLastAppliedAt(LocalDateTime.now());
                    // A successful apply reconciles everything, so any recorded drift is resolved.
                    stack.setDriftDetected(false);
                    stack.setDriftSummary(null);
                    applyOutputs(stack, result.outputsJson());
                }
                case REFRESH -> {
                    stack.setLastDriftCheckAt(LocalDateTime.now());
                    boolean drifted = result.hasAnyChange();
                    stack.setDriftDetected(drifted);
                    stack.setDriftSummary(drifted
                        ? "Refresh found " + nz(result.resourcesToChange()) + " changed and "
                          + nz(result.resourcesToDestroy()) + " missing resource(s). "
                          + "Something was modified outside Control Center."
                        : null);
                    if (drifted) stack.setStatus(InfrastructureStack.Status.DRIFTED);
                    applyOutputs(stack, result.outputsJson());
                }
                case DESTROY -> {
                    stack.setPublicUrl(null);
                    stack.setWebUrl(null);
                    stack.setOutputsJson(null);
                }
            }

            stackRepo.save(stack);
            syncOrganization(stack, run.getAction());
        });

        log.info("Provisioning run {} ({}) succeeded", runId, run.getAction());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void finishFailed(UUID runId, String runLog, String error) {
        ProvisioningRun run = runRepo.findById(runId).orElse(null);
        if (run == null) return;

        run.setStatus(ProvisioningRun.Status.FAILED);
        run.setCompletedAt(LocalDateTime.now());
        run.setLog(truncate(runLog));
        run.setErrorMessage(error);
        runRepo.save(run);

        stackRepo.findById(run.getStackId()).ifPresent(stack -> {
            // A failed PLAN leaves the infrastructure untouched, so the stack keeps whatever status
            // it had — it is not "failed", the plan was. A failed APPLY or DESTROY may have changed
            // things partway and does need the failed status.
            if (run.getAction() != ProvisioningRun.Action.PLAN) {
                stack.setStatus(InfrastructureStack.Status.FAILED);
                stackRepo.save(stack);
            } else if (stack.getStatus() == InfrastructureStack.Status.PLANNING) {
                stack.setStatus(stack.getLastAppliedAt() != null
                    ? InfrastructureStack.Status.ACTIVE
                    : InfrastructureStack.Status.DRAFT);
                stackRepo.save(stack);
            }
        });

        log.warn("Provisioning run {} ({}) failed: {}", runId, run.getAction(), error);
    }

    // ── Outputs ─────────────────────────────────────────────────────────────

    /** Copy the handful of values worth denormalising out of {@code terraform output -json}. */
    private void applyOutputs(InfrastructureStack stack, String outputsJson) {
        if (outputsJson == null || outputsJson.isBlank()) return;
        stack.setOutputsJson(outputsJson);
        try {
            JsonNode root = mapper.readTree(outputsJson);
            stack.setPublicUrl(outputValue(root, "public_url"));
            stack.setWebUrl(outputValue(root, "web_url"));
            stack.setFingerprint(outputValue(root, "fingerprint"));
        } catch (Exception e) {
            log.warn("Could not parse terraform outputs for stack {}: {}", stack.getId(), e.getMessage());
        }
    }

    /** {@code terraform output -json} wraps every value as {"value": ..., "type": ...}. */
    private String outputValue(JsonNode root, String name) {
        JsonNode node = root.path(name).path("value");
        return node.isMissingNode() || node.isNull() || !node.isTextual() || node.asText().isBlank()
            ? null : node.asText();
    }

    /**
     * Point the Organization record at what was just provisioned, so health checks, webhook delivery
     * and the instance registry all address the real deployment.
     */
    private void syncOrganization(InfrastructureStack stack, ProvisioningRun.Action action) {
        orgRepo.findById(stack.getOrganizationId()).ifPresent(org -> {
            boolean changed = false;

            if (action == ProvisioningRun.Action.APPLY && stack.getPublicUrl() != null) {
                if (!stack.getPublicUrl().equals(org.getBackendUrl())) {
                    org.setBackendUrl(stack.getPublicUrl());
                    changed = true;
                }
                // PROVISIONING until the instance actually reports in — an apply proves the
                // infrastructure exists, not that ZGATE booted successfully inside it.
                if (org.getDeploymentStatus() != Organization.DeploymentStatus.HEALTHY) {
                    org.setDeploymentStatus(Organization.DeploymentStatus.PROVISIONING);
                    changed = true;
                }
            }

            if (action == ProvisioningRun.Action.DESTROY) {
                org.setDeploymentStatus(Organization.DeploymentStatus.OFFLINE);
                org.setBackendUrl(null);
                changed = true;
            }

            if (changed) orgRepo.save(org);
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_LOG_CHARS) return s;
        return s.substring(0, MAX_LOG_CHARS)
             + "\n\n[log truncated at " + MAX_LOG_CHARS + " characters]";
    }

    /** The runner reports -1 for "no parseable plan"; store null rather than a misleading -1. */
    private Integer nonNegative(Integer v) {
        return v == null || v < 0 ? null : v;
    }

    private int nz(Integer v) { return v == null || v < 0 ? 0 : v; }
}
