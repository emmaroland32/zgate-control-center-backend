package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.ConfigSnapshot;
import com.zgate.controlcenter.domain.ControlCenterConfig;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.ConfigSnapshotRepository;
import com.zgate.controlcenter.repository.ControlCenterConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ControlCenterConfigService {

    private final ControlCenterConfigRepository repo;
    private final ConfigSnapshotRepository snapshotRepo;
    private final ObjectMapper objectMapper;

    @Cacheable("controlcenter-config")
    public List<ControlCenterConfig> findAll() {
        return repo.findAll();
    }

    public List<ControlCenterConfig> findByCategory(String category) {
        return repo.findByCategory(category);
    }

    public ControlCenterConfig findByKey(String key) {
        return repo.findByConfigKey(key)
            .orElseThrow(() -> new ControlCenterException("Config key not found: " + key));
    }

    @CacheEvict(value = "controlcenter-config", allEntries = true)
    public ControlCenterConfig upsert(String key, String value, String updatedBy) {
        ControlCenterConfig config = repo.findByConfigKey(key)
            .orElseGet(() -> ControlCenterConfig.builder()
                .configKey(key)
                .category("GENERAL")
                .build());
        config.setValue(value);
        config.setUpdatedBy(updatedBy);
        return repo.save(config);
    }

    @CacheEvict(value = "controlcenter-config", allEntries = true)
    public List<ControlCenterConfig> updateBatch(List<Map<String, String>> updates, String updatedBy) {
        List<ControlCenterConfig> saved = new ArrayList<>();
        for (Map<String, String> entry : updates) {
            String key = entry.get("key");
            String value = entry.get("value");
            if (key == null) {
                throw new ControlCenterException("Batch entry missing required field: key");
            }
            ControlCenterConfig config = repo.findByConfigKey(key)
                .orElseGet(() -> ControlCenterConfig.builder()
                    .configKey(key)
                    .category("GENERAL")
                    .build());
            config.setValue(value);
            config.setUpdatedBy(updatedBy);
            saved.add(repo.save(config));
        }
        return saved;
    }

    public Map<String, Object> testRegistryConnection(String url, String username, String password) {
        if (url == null || url.isBlank()) {
            return Map.of("ok", false, "error", "Registry URL is required");
        }
        String testUrl = url.startsWith("http") ? url : "https://" + url;
        if (!testUrl.endsWith("/v2/")) testUrl = testUrl + "/v2/";
        long start = System.nanoTime();
        try {
            var conn = (java.net.HttpURLConnection) new java.net.URL(testUrl).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            if (username != null && password != null && !username.isBlank()) {
                String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
                conn.setRequestProperty("Authorization", "Basic " + auth);
            }
            int code = conn.getResponseCode();
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            boolean ok = code == 200 || code == 401; // 401 means registry exists but needs auth
            return Map.of("ok", ok, "latencyMs", latencyMs, "statusCode", code);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return Map.of("ok", false, "latencyMs", latencyMs, "error", e.getMessage());
        }
    }

    // ── Snapshots ──────────────────────────────────────────────
    public List<ConfigSnapshot> findAllSnapshots() {
        return snapshotRepo.findAllByOrderByTakenAtDesc();
    }

    public ConfigSnapshot takeSnapshot(String organizationId, String takenBy, String note) {
        List<ControlCenterConfig> allConfigs = repo.findAll();
        // Serialize as JSON array of {key, value} pairs
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < allConfigs.size(); i++) {
            ControlCenterConfig c = allConfigs.get(i);
            sb.append("{\"key\":\"").append(escapeJson(c.getConfigKey()))
              .append("\",\"value\":\"").append(escapeJson(c.getValue() != null ? c.getValue() : ""))
              .append("\"}");
            if (i < allConfigs.size() - 1) sb.append(",");
        }
        sb.append("]");

        ConfigSnapshot snapshot = ConfigSnapshot.builder()
            .organizationId(organizationId)
            .takenBy(takenBy)
            .note(note != null ? note : "Manual snapshot")
            .entryCount(allConfigs.size())
            .snapshotData(sb.toString())
            .build();
        return snapshotRepo.save(snapshot);
    }

    @Transactional
    @CacheEvict(value = "controlcenter-config", allEntries = true)
    public void restoreSnapshot(UUID snapshotId, String restoredBy) {
        ConfigSnapshot snapshot = snapshotRepo.findById(snapshotId)
            .orElseThrow(() -> new ControlCenterException("Snapshot not found: " + snapshotId));
        String data = snapshot.getSnapshotData();
        try {
            List<Map<String, String>> entries = objectMapper.readValue(data,
                new TypeReference<List<Map<String, String>>>() {});
            for (Map<String, String> entry : entries) {
                String key = entry.get("key");
                String value = entry.get("value");
                if (key != null) {
                    upsert(key, value, restoredBy);
                }
            }
        } catch (Exception e) {
            throw new ControlCenterException("Failed to restore snapshot: invalid data format");
        }
    }

    public void deleteSnapshot(UUID snapshotId) {
        if (!snapshotRepo.existsById(snapshotId)) {
            throw new ControlCenterException("Snapshot not found: " + snapshotId);
        }
        snapshotRepo.deleteById(snapshotId);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    @CacheEvict(value = "controlcenter-config", allEntries = true)
    public void delete(String key) {
        ControlCenterConfig config = repo.findByConfigKey(key)
            .orElseThrow(() -> new ControlCenterException("Config key not found: " + key));
        repo.delete(config);
    }
}
