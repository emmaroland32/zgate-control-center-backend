package com.zgate.controlcenter.service.provisioning;

import com.zgate.controlcenter.domain.ProvisioningRun;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Executes one terraform action against one stack.
 *
 * <p>An interface, not a class, because the execution backend is a deployment decision: the shipped
 * implementation runs a container on the Control Center host, but the same contract fits a remote
 * worker, a CI dispatch, or Terraform Cloud without any caller changing.
 */
public interface TerraformRunner {

    /**
     * Run one action to completion.
     *
     * @param request     what to run and against which stack
     * @param logConsumer receives output line by line as it is produced, so the UI can stream a live
     *                    log rather than showing nothing for the several minutes an apply takes
     */
    Result run(Request request, Consumer<String> logConsumer);

    /** True when this runner can actually execute — e.g. the container runtime is reachable. */
    boolean isAvailable();

    /** Why {@link #isAvailable()} is false, for a diagnostic the operator can act on. */
    String unavailableReason();

    /**
     * @param target      stack directory name: aws-ecs | aws-ec2 | azure-aca | gcp-cloudrun
     * @param specJson    the rendered tfvars, INCLUDING any run-time secrets. Written to a file in
     *                    the run workspace, never passed as a command-line argument where it would
     *                    appear in {@code ps} output.
     * @param environment provider credentials and state configuration. Values are secret; an
     *                    implementation must not log this map.
     * @param confirmDestroy must be set for a DESTROY to be permitted, on top of the spec's own
     *                    {@code guards.allow_destroy}. Two independent confirmations.
     */
    record Request(
        java.util.UUID organizationId,
        String environmentName,
        String target,
        ProvisioningRun.Action action,
        String specJson,
        Map<String, String> environment,
        boolean confirmDestroy,
        int timeoutSeconds
    ) {}

    /**
     * @param outputsJson {@code terraform output -json}, present after a successful apply/refresh
     * @param planJson    machine-readable plan, present after a plan/apply
     */
    record Result(
        boolean success,
        int exitCode,
        String log,
        String outputsJson,
        String planJson,
        Integer resourcesToAdd,
        Integer resourcesToChange,
        Integer resourcesToDestroy,
        String errorMessage
    ) {
        /**
         * True when the run found anything that is not already as configured.
         *
         * <p>A negative count means "no parseable plan", not "no changes" — treating that as clean
         * would silently report a drifted stack as healthy, so it is excluded rather than counted.
         */
        public boolean hasAnyChange() {
            return positive(resourcesToAdd) || positive(resourcesToChange) || positive(resourcesToDestroy);
        }

        private static boolean positive(Integer v) {
            return v != null && v > 0;
        }
    }
}
