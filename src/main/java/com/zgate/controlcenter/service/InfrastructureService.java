package com.zgate.controlcenter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InfrastructureService {

    // ── DTOs ──────────────────────────────────────────────────
    public record ContainerInfo(
        String id, String name, String image, String status,
        String state, String ports, long memoryBytes, long memoryLimitBytes,
        double cpuPercent, int restartCount, String health, String orgId
    ) {}

    public record VolumeInfo(
        String name, String driver, String mountpoint,
        long sizeBytes, Map<String, String> labels
    ) {}

    public record ContainerStats(
        String id, String name, double cpuPercent,
        long memUsage, long memLimit, String netIO, String blockIO
    ) {}

    // ── Container Operations ────────────────────────────────────
    public List<ContainerInfo> listContainers() {
        String output = exec("docker", "ps", "-a", "--no-trunc",
            "--format", "{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}|{{.State}}|{{.Ports}}");
        if (output == null) return List.of();
        return output.lines()
            .filter(l -> !l.isBlank())
            .map(line -> {
                String[] parts = line.split("\\|", -1);
                return new ContainerInfo(
                    parts.length > 0 ? parts[0] : "",
                    parts.length > 1 ? parts[1] : "",
                    parts.length > 2 ? parts[2] : "",
                    parts.length > 3 ? parts[3] : "",
                    parts.length > 4 ? parts[4] : "",
                    parts.length > 5 ? parts[5] : "",
                    0, 0, 0, 0, "unknown", ""
                );
            })
            .collect(Collectors.toList());
    }

    public void startContainer(String containerId) {
        exec("docker", "start", containerId);
    }

    public void stopContainer(String containerId) {
        exec("docker", "stop", containerId);
    }

    public void restartContainer(String containerId) {
        exec("docker", "restart", containerId);
    }

    public String getContainerLogs(String containerId, int tailLines) {
        String output = exec("docker", "logs", "--tail", String.valueOf(tailLines), containerId);
        return output != null ? output : "";
    }

    public List<ContainerStats> getContainerStats() {
        String output = exec("docker", "stats", "--no-stream", "--no-trunc",
            "--format", "{{.ID}}|{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}");
        if (output == null) return List.of();
        return output.lines()
            .filter(l -> !l.isBlank())
            .map(line -> {
                String[] parts = line.split("\\|", -1);
                double cpu = 0;
                try { cpu = Double.parseDouble(parts[2].replace("%", "").trim()); } catch (Exception ignored) {}
                return new ContainerStats(
                    parts.length > 0 ? parts[0] : "",
                    parts.length > 1 ? parts[1] : "",
                    cpu, 0, 0,
                    parts.length > 5 ? parts[5] : "",
                    parts.length > 6 ? parts[6] : ""
                );
            })
            .collect(Collectors.toList());
    }

    // ── Volume Operations ────────────────────────────────────────
    public List<VolumeInfo> listVolumes() {
        String output = exec("docker", "volume", "ls", "--format", "{{.Name}}|{{.Driver}}|{{.Mountpoint}}");
        if (output == null) return List.of();
        return output.lines()
            .filter(l -> !l.isBlank())
            .map(line -> {
                String[] parts = line.split("\\|", -1);
                return new VolumeInfo(
                    parts.length > 0 ? parts[0] : "",
                    parts.length > 1 ? parts[1] : "",
                    parts.length > 2 ? parts[2] : "",
                    0, Map.of()
                );
            })
            .collect(Collectors.toList());
    }

    public void deleteVolume(String volumeName) {
        exec("docker", "volume", "rm", volumeName);
    }

    // ── Docker Compose ────────────────────────────────────────────
    public String getDockerComposeConfig() {
        String output = exec("docker", "compose", "config");
        return output != null ? output : "";
    }

    // ── Resource Usage ────────────────────────────────────────────
    public Map<String, Object> getResourceUsage() {
        Map<String, Object> usage = new LinkedHashMap<>();
        String dfOutput = exec("docker", "system", "df", "--format", "{{.Type}}|{{.TotalCount}}|{{.Active}}|{{.Size}}|{{.Reclaimable}}");
        usage.put("systemDf", dfOutput != null ? dfOutput : "");
        return usage;
    }

    // ── Helper ────────────────────────────────────────────────────
    private String exec(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            int exit = process.waitFor();
            if (exit != 0) {
                log.warn("Command {} exited with code {}: {}", String.join(" ", cmd), exit, output);
            }
            return output;
        } catch (Exception e) {
            log.error("Failed to execute command: {}", String.join(" ", cmd), e);
            return null;
        }
    }
}
