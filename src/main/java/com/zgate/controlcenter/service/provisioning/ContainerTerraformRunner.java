package com.zgate.controlcenter.service.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.domain.ProvisioningRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs terraform in a throwaway container on the Control Center host.
 *
 * <p>Shape of a run:
 * <pre>
 *   docker run --rm
 *     -v &lt;deployDir&gt;:/deploy:ro          the stacks, read-only so a run cannot mutate them
 *     -v &lt;workDir&gt;:/workspace             spec in, plan/outputs out
 *     -v zgate-tf-plugin-cache:/workspace/.terraform-plugin-cache
 *     -e ...credentials...                never on the command line
 *     zgate-provisioning-runner:&lt;tag&gt; &lt;action&gt;
 * </pre>
 *
 * <p>Two things are deliberate:
 * <ul>
 *   <li><b>Credentials go in the environment, not argv.</b> Anything on the command line is visible
 *       in {@code ps} to every process on the host. {@code ProcessBuilder.environment()} is not.</li>
 *   <li><b>The spec goes in a file, not argv,</b> for the same reason — it can carry a customer's
 *       external database password.</li>
 * </ul>
 *
 * <p>The workspace is deleted in a finally block whether the run succeeded or not, because it holds
 * the rendered spec.
 */
@Component
@Slf4j
public class ContainerTerraformRunner implements TerraformRunner {

    private final ObjectMapper mapper;
    private final String containerRuntime;
    private final String runnerImage;
    private final Path deployDir;
    private final Path workRoot;
    private final String pluginCacheVolume;
    private final boolean enabled;

    public ContainerTerraformRunner(
            ObjectMapper mapper,
            @Value("${controlcenter.provisioning.enabled:false}") boolean enabled,
            @Value("${controlcenter.provisioning.containerRuntime:docker}") String containerRuntime,
            @Value("${controlcenter.provisioning.runnerImage:zgate-provisioning-runner:1.0.0}") String runnerImage,
            @Value("${controlcenter.provisioning.deployDir:}") String deployDir,
            @Value("${controlcenter.provisioning.workDir:/var/lib/zgate-control-center/provisioning}") String workDir,
            @Value("${controlcenter.provisioning.pluginCacheVolume:zgate-tf-plugin-cache}") String pluginCacheVolume) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.containerRuntime = containerRuntime;
        this.runnerImage = runnerImage;
        this.deployDir = deployDir == null || deployDir.isBlank() ? null : Paths.get(deployDir).toAbsolutePath();
        this.workRoot = Paths.get(workDir).toAbsolutePath();
        this.pluginCacheVolume = pluginCacheVolume;
    }

    @Override
    public boolean isAvailable() {
        return unavailableReason() == null;
    }

    @Override
    public String unavailableReason() {
        if (!enabled) {
            return "Provisioning is disabled. Set controlcenter.provisioning.enabled=true to turn it on.";
        }
        if (deployDir == null) {
            return "controlcenter.provisioning.deployDir is not set — it must point at the deploy/ "
                 + "directory holding the Terraform stacks.";
        }
        if (!Files.isDirectory(deployDir.resolve("stacks"))) {
            return "controlcenter.provisioning.deployDir (" + deployDir + ") does not contain a stacks/ directory.";
        }
        try {
            Process p = new ProcessBuilder(containerRuntime, "version").redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "The container runtime '" + containerRuntime + "' did not respond within 10s.";
            }
            if (p.exitValue() != 0) {
                return "The container runtime '" + containerRuntime + "' is not usable (exit " + p.exitValue() + ").";
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "Could not reach the container runtime '" + containerRuntime + "': " + e.getMessage();
        }
        return null;
    }

    @Override
    public Result run(Request request, Consumer<String> logConsumer) {
        String reason = unavailableReason();
        if (reason != null) {
            return failure(-1, "", "Provisioning runner unavailable: " + reason);
        }

        Path workDir = null;
        try {
            workDir = prepareWorkspace(request);
            return execute(request, workDir, logConsumer);
        } catch (Exception e) {
            log.error("Provisioning run failed for org {} ({}): {}",
                      request.organizationId(), request.action(), e.getMessage());
            return failure(-1, "", e.getMessage());
        } finally {
            // The workspace holds the rendered spec, which can carry a customer's external database
            // password. Remove it whether the run succeeded or not.
            deleteRecursively(workDir);
        }
    }

    // ── Workspace ───────────────────────────────────────────────────────────

    private Path prepareWorkspace(Request request) throws IOException {
        Files.createDirectories(workRoot);
        Path dir = Files.createTempDirectory(workRoot, "run-");
        restrictToOwner(dir);

        Path spec = dir.resolve("spec.tfvars.json");
        Files.writeString(spec, request.specJson(), StandardCharsets.UTF_8);
        restrictToOwner(spec);

        return dir;
    }

    /** Best-effort 0700/0600. Skipped silently on a filesystem without POSIX permissions. */
    private void restrictToOwner(Path path) {
        try {
            boolean dir = Files.isDirectory(path);
            Set<PosixFilePermission> perms = dir
                ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                             PosixFilePermission.OWNER_EXECUTE)
                : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("Could not restrict permissions on {}: {}", path, e.getMessage());
        }
    }

    // ── Execution ───────────────────────────────────────────────────────────

    private Result execute(Request request, Path workDir, Consumer<String> logConsumer)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(List.of(
            containerRuntime, "run", "--rm",
            "-v", deployDir + ":/deploy:ro",
            "-v", workDir + ":/workspace",
            "-v", pluginCacheVolume + ":/workspace/.terraform-plugin-cache"
        ));

        // Pass credential NAMES on the command line and the VALUES through the process environment.
        // `docker run -e KEY` (no value) forwards the value from our own environment, so nothing
        // secret is ever an argv element visible in `ps`.
        Map<String, String> env = new LinkedHashMap<>(request.environment());
        env.put("ZGATE_TARGET", request.target());
        env.put("ZGATE_ORG_ID", request.organizationId().toString());
        env.put("ZGATE_ENVIRONMENT", request.environmentName());
        if (request.action() == ProvisioningRun.Action.DESTROY && request.confirmDestroy()) {
            env.put("ZGATE_CONFIRM_DESTROY", "yes-destroy-this-deployment");
        }

        for (String key : env.keySet()) {
            cmd.add("-e");
            cmd.add(key);
        }

        cmd.add(runnerImage);
        cmd.add(switch (request.action()) {
            case PLAN -> "plan";
            case APPLY -> "apply";
            case DESTROY -> "destroy";
            case REFRESH -> "refresh";
        });

        log.info("Provisioning {} for org {} target {} ({})",
                 request.action(), request.organizationId(), request.target(), runnerImage);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder logBuffer = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logBuffer.append(line).append('\n');
                if (logConsumer != null) {
                    try {
                        logConsumer.accept(line);
                    } catch (RuntimeException e) {
                        // A failing log consumer must never abort the terraform run itself —
                        // killing an apply halfway is far worse than losing a log line.
                        log.warn("Log consumer threw: {}", e.getMessage());
                    }
                }
            }
        }

        boolean finished = process.waitFor(Math.max(60, request.timeoutSeconds()), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            // A killed apply may have created real resources. Say so plainly: the next action must
            // be a plan against the existing state, not a retry from scratch.
            return failure(-1, logBuffer.toString(),
                "The run exceeded its " + request.timeoutSeconds() + "s timeout and was terminated. "
                + "Infrastructure may have been partially created — run a plan to see the current state "
                + "before retrying.");
        }

        int exit = process.exitValue();
        String outputs = readIfPresent(workDir.resolve("outputs.json"));
        String planJson = readIfPresent(workDir.resolve("plan.json"));
        int[] counts = parsePlanCounts(planJson);

        if (exit != 0) {
            return new Result(false, exit, logBuffer.toString(), outputs, planJson,
                counts[0], counts[1], counts[2], extractError(logBuffer.toString()));
        }

        return new Result(true, exit, logBuffer.toString(), outputs, planJson,
            counts[0], counts[1], counts[2], null);
    }

    // ── Plan parsing ────────────────────────────────────────────────────────

    /**
     * Counts create/update/delete actions in {@code terraform show -json}. A resource being REPLACED
     * appears as both a delete and a create, and is counted in both — that is the honest reading: a
     * replacement really does destroy something.
     *
     * @return {add, change, destroy}, all null when there is no parseable plan
     */
    private int[] parsePlanCounts(String planJson) {
        if (planJson == null || planJson.isBlank()) return new int[]{-1, -1, -1};
        try {
            JsonNode root = mapper.readTree(planJson);
            JsonNode changes = root.path("resource_changes");
            if (!changes.isArray()) return new int[]{0, 0, 0};

            int add = 0, change = 0, destroy = 0;
            for (JsonNode rc : changes) {
                for (JsonNode action : rc.path("change").path("actions")) {
                    switch (action.asText()) {
                        case "create" -> add++;
                        case "update" -> change++;
                        case "delete" -> destroy++;
                        default -> { /* "no-op" and "read" are not changes */ }
                    }
                }
            }
            return new int[]{add, change, destroy};
        } catch (Exception e) {
            log.warn("Could not parse the terraform plan JSON: {}", e.getMessage());
            return new int[]{-1, -1, -1};
        }
    }

    /** Pull the first Terraform error out of the log, for a summary that is not the whole transcript. */
    private String extractError(String log) {
        if (log == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean capturing = false;
        for (String line : log.split("\n")) {
            if (line.startsWith("Error:") || line.contains("[zgate-provision] ERROR:")) {
                capturing = true;
            } else if (capturing && line.isBlank()) {
                break;
            }
            if (capturing) {
                sb.append(line).append('\n');
                if (sb.length() > 4000) break;
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "The run failed; see the log for details." : out;
    }

    private String readIfPresent(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            log.warn("Could not read {}: {}", path, e.getMessage());
            return null;
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean the run workspace {}: {}", dir, e.getMessage());
        }
    }

    private Result failure(int exit, String log, String message) {
        return new Result(false, exit, log, null, null, null, null, null, message);
    }
}
