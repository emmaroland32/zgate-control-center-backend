package com.zgate.controlcenter.service;

import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DatabaseService {

    private final DataSource dataSource;
    private final Flyway flyway;

    // ── DTOs ──────────────────────────────────────────────────────────
    public record DatabaseHealth(
        String status, String version, long sizeBytes,
        int activeConnections, int maxConnections,
        int pendingMigrations, List<SchemaInfo> schemas
    ) {}

    public record SchemaInfo(String name, long sizeBytes, int tableCount) {}

    public record MigrationEntry(
        String version, String description, String state,
        String type, String installedOn, int executionTime, String schema
    ) {}

    public record BackupEntry(
        String id, String name, long sizeBytes, String status,
        String createdAt, String completedAt
    ) {}

    // ── Test Connection ────────────────────────────────────────────────
    public Map<String, Object> testConnection() {
        long start = System.nanoTime();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return Map.of("ok", true, "latencyMs", latencyMs);
        } catch (Exception e) {
            return Map.of("ok", false, "latencyMs", 0, "error", e.getMessage());
        }
    }

    // ── Health ────────────────────────────────────────────────────────
    public DatabaseHealth getHealth() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Version
            ResultSet vrs = stmt.executeQuery("SELECT version()");
            vrs.next();
            String version = vrs.getString(1);

            // DB size
            ResultSet srs = stmt.executeQuery(
                "SELECT pg_database_size(current_database())");
            srs.next();
            long sizeBytes = srs.getLong(1);

            // Active connections
            ResultSet ars = stmt.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE state = 'active'");
            ars.next();
            int activeConns = ars.getInt(1);

            // Max connections
            ResultSet mrs = stmt.executeQuery("SHOW max_connections");
            mrs.next();
            int maxConns = Integer.parseInt(mrs.getString(1));

            // Pending migrations
            int pending = 0;
            for (MigrationInfo info : flyway.info().all()) {
                if (info.getState().name().contains("PENDING")) pending++;
            }

            // Schemas
            List<SchemaInfo> schemas = getSchemas(conn);

            return new DatabaseHealth("UP", version, sizeBytes,
                activeConns, maxConns, pending, schemas);
        } catch (Exception e) {
            return new DatabaseHealth("DOWN", "unknown", 0, 0, 0, 0, List.of());
        }
    }

    private List<SchemaInfo> getSchemas(Connection conn) throws Exception {
        List<SchemaInfo> schemas = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT schema_name FROM information_schema.schemata " +
                 "WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast') " +
                 "ORDER BY schema_name")) {
            while (rs.next()) {
                String name = rs.getString(1);
                // Schema size
                long size = 0;
                try (Statement s2 = conn.createStatement();
                     ResultSet sr = s2.executeQuery(
                         "SELECT COALESCE(sum(pg_total_relation_size(quote_ident(schemaname) || '.' || quote_ident(tablename))), 0) " +
                         "FROM pg_tables WHERE schemaname = '" + name.replace("'", "''") + "'")) {
                    if (sr.next()) size = sr.getLong(1);
                }
                // Table count
                int tableCount = 0;
                try (Statement s3 = conn.createStatement();
                     ResultSet tr = s3.executeQuery(
                         "SELECT count(*) FROM pg_tables WHERE schemaname = '" + name.replace("'", "''") + "'")) {
                    if (tr.next()) tableCount = tr.getInt(1);
                }
                schemas.add(new SchemaInfo(name, size, tableCount));
            }
        }
        return schemas;
    }

    // ── Migrations ────────────────────────────────────────────────────
    public List<MigrationEntry> getMigrations() {
        List<MigrationEntry> list = new ArrayList<>();
        for (MigrationInfo info : flyway.info().all()) {
            list.add(new MigrationEntry(
                info.getVersion() != null ? info.getVersion().getVersion() : "",
                info.getDescription(),
                info.getState().name(),
                info.getType().name(),
                info.getInstalledOn() != null ? info.getInstalledOn().toInstant().toString() : null,
                info.getExecutionTime() != null ? info.getExecutionTime() : 0,
                "public"
            ));
        }
        return list;
    }

    public void runMigrations() {
        flyway.migrate();
    }

    public Map<String, Object> validateSchema() {
        var result = flyway.validateWithResult();
        return Map.of(
            "valid", result.validationSuccessful,
            "errorCount", result.invalidMigrations.size(),
            "warnings", result.warnings
        );
    }

    // ── Backups (placeholder — real impl would use pg_dump) ──────────
    private final List<BackupEntry> backups = Collections.synchronizedList(new ArrayList<>());

    public List<BackupEntry> getBackups() {
        return List.copyOf(backups);
    }

    private static final int MAX_BACKUP_HISTORY = 100;

    public BackupEntry createBackup(String name) {
        BackupEntry entry = new BackupEntry(
            UUID.randomUUID().toString(), name, 0, "COMPLETED",
            LocalDateTime.now().toString(), LocalDateTime.now().toString()
        );
        backups.add(0, entry);
        if (backups.size() > MAX_BACKUP_HISTORY) {
            backups.subList(MAX_BACKUP_HISTORY, backups.size()).clear();
        }
        return entry;
    }

    public void deleteBackup(String id) {
        backups.removeIf(b -> b.id().equals(id));
    }
}
