package com.zgate.controlcenter.service.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.domain.CloudCredential;
import com.zgate.controlcenter.exception.ControlCenterException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Answers "can we actually deploy to this server?" before any provisioning starts.
 *
 * <p>Runs the same runner image the provisioning itself uses, in {@code ssh-check} mode. That probe
 * is strictly read-only — it connects, inspects and changes nothing — so an operator can run it as
 * often as they like against a customer's machine while they sort out access.
 *
 * <p>It exists because the alternative is finding out mid-install that the box has 2 GB of RAM, or
 * that the login user cannot sudo, and being left with a half-configured machine and an error
 * nobody can act on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SshReachabilityService {

    private final CloudCredentialService credentials;
    private final ObjectMapper mapper;

    @Value("${controlcenter.provisioning.enabled:false}")
    private boolean enabled;

    @Value("${controlcenter.provisioning.containerRuntime:docker}")
    private String containerRuntime;

    @Value("${controlcenter.provisioning.runnerImage:zgate-provisioning-runner:1.0.0}")
    private String runnerImage;

    @Value("${controlcenter.provisioning.sshCheckTimeoutSeconds:120}")
    private int timeoutSeconds;

    /**
     * Probe the server behind a stored credential.
     *
     * @param minMemoryMb minimum RAM the intended deployment needs; the probe fails the memory check
     *                    below this rather than letting the first migration be OOM-killed
     * @param minDiskGb   minimum free space where the data directory will live
     */
    public Map<String, Object> check(UUID credentialId, int minMemoryMb, int minDiskGb) {
        if (!enabled) {
            throw new ControlCenterException(
                "Provisioning is disabled. Set controlcenter.provisioning.enabled=true to use the "
                + "reachability check.",
                "PROVISIONING_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }

        CloudCredential cred = credentials.loadForProvisioning(credentialId);
        if (cred.getAuthMode() != CloudCredential.AuthMode.SSH_KEY) {
            throw new ControlCenterException(
                "Credential '" + cred.getDisplayName() + "' is not an SSH key, so there is no server to reach.",
                "CREDENTIAL_NOT_SSH", HttpStatus.BAD_REQUEST);
        }

        Map<String, String> env = new LinkedHashMap<>();
        env.put("ZGATE_SSH_HOST", nullSafe(cred.getSshHost()));
        env.put("ZGATE_SSH_PORT", String.valueOf(cred.getSshPort() == null ? 22 : cred.getSshPort()));
        env.put("ZGATE_SSH_USER", nullSafe(cred.getSshUser()));
        env.put("ZGATE_SSH_KEY", credentials.sshPrivateKey(credentialId));
        env.put("ZGATE_SSH_HOST_KEY", nullSafe(cred.getSshHostPublicKey()));
        env.put("ZGATE_MIN_MEMORY_MB", String.valueOf(Math.max(1024, minMemoryMb)));
        env.put("ZGATE_MIN_DISK_GB", String.valueOf(Math.max(10, minDiskGb)));
        env.put("ZGATE_CHECK_OUTPUT", "/tmp/ssh-check.json");

        List<String> cmd = new ArrayList<>(List.of(containerRuntime, "run", "--rm"));
        // Credential NAMES on the command line, VALUES through the process environment — anything
        // in argv is visible in `ps` to every process on this host.
        for (String key : env.keySet()) {
            cmd.add("-e");
            cmd.add(key);
        }
        cmd.add(runnerImage);
        cmd.add("ssh-check");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(env);
            // Keep stderr separate: the probe's stdout is the JSON document, and merging would
            // corrupt it with any stray diagnostic line.
            pb.redirectErrorStream(false);

            Process process = pb.start();

            String stdout;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                stdout = String.join("\n", r.lines().toList());
            }

            if (!process.waitFor(Math.max(30, timeoutSeconds), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return unreachable("timeout",
                    "The reachability check did not finish within " + timeoutSeconds + "s. The server is "
                    + "most likely unreachable from Control Center and the connection is hanging rather "
                    + "than being refused — usually a firewall dropping packets silently.");
            }

            if (stdout.isBlank()) {
                return unreachable("no_output",
                    "The reachability check produced no output. Verify the runner image '"
                    + runnerImage + "' is present on this host.");
            }

            JsonNode root = mapper.readTree(stdout);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.convertValue(root, Map.class);
            return result;

        } catch (ControlCenterException e) {
            throw e;
        } catch (Exception e) {
            log.error("SSH reachability check failed for credential {}: {}", credentialId, e.toString());
            return unreachable("error", "The reachability check could not be run: " + e.getMessage());
        }
    }

    /** Shaped exactly like the probe's own output, so the UI renders one thing either way. */
    private Map<String, Object> unreachable(String name, String detail) {
        return Map.of(
            "reachable", false,
            "checks", List.of(Map.of("name", name, "status", "fail", "detail", detail)));
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
