package com.zgate.controlcenter.service;

import com.zgate.controlcenter.exception.ControlCenterException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Backup and restore of <b>Control Center's own</b> database, via {@code pg_dump}/{@code pg_restore}.
 *
 * <p>This replaces an in-memory list that recorded backups which never existed: the console showed
 * rows, a size of 0, and a "Restore" button that did nothing. A backup you cannot restore is not a
 * backup, and a restore button that silently no-ops is worse than no button.
 *
 * <p>Not to be confused with {@link BackupService}, which handles <i>customer</i> deployments'
 * managed backups (client-encrypted, streamed to S3). This one protects the control plane itself:
 * organizations, licences, entitlements, provisioning specs and the audit trail.
 *
 * <p><b>Off unless a directory is configured.</b> Restore additionally needs its own flag, because
 * it overwrites the live control-plane database — that is a two-key action, not a toggle.
 */
@Service
@Slf4j
@lombok.RequiredArgsConstructor
public class ControlCenterBackupService {

    /** Marker file each replica drops in the backup directory, so we can tell if it is shared. */
    private static final String NODE_MARKER_PREFIX = ".cc-node-";

    private final ReplicaRegistry replicas;

    /** Backup file names are generated, but the id arrives from a request — keep it inert. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,120}");

    @Value("${controlcenter.database.backup.directory:}")
    private String directory;

    @Value("${controlcenter.database.backup.pgDumpPath:pg_dump}")
    private String pgDumpPath;

    @Value("${controlcenter.database.backup.pgRestorePath:pg_restore}")
    private String pgRestorePath;

    @Value("${controlcenter.database.backup.restoreEnabled:false}")
    private boolean restoreEnabled;

    @Value("${controlcenter.database.backup.timeoutSeconds:1800}")
    private int timeoutSeconds;

    @Value("${spring.datasource.url:}")
    private String jdbcUrl;

    @Value("${spring.datasource.username:}")
    private String dbUsername;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    public record BackupFile(String id, String name, long sizeBytes, String status,
                             String createdAt, String completedAt) {}

    /** Why backups are unavailable, or null when they are ready. */
    public String unavailableReason() {
        if (directory == null || directory.isBlank()) {
            return "controlcenter.database.backup.directory is not set. Point it at a durable, "
                 + "restricted path (it will hold full database dumps) to enable backups.";
        }
        return null;
    }

    public boolean isRestoreEnabled() {
        return restoreEnabled;
    }

    /**
     * Announce this replica's presence in the backup directory.
     *
     * <p>If the directory is shared storage every replica's marker is visible from every replica.
     * If it is node-local, each one only ever sees its own — which is exactly the condition that
     * makes a backup taken on one node unrestorable from another.
     */
    @jakarta.annotation.PostConstruct
    void markThisNode() {
        if (unavailableReason() != null) return;
        try {
            Path dir = Path.of(directory);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(NODE_MARKER_PREFIX + nodeName()),
                              LocalDateTime.now().toString());
        } catch (IOException | RuntimeException e) {
            log.debug("Could not write the node marker: {}", e.toString());
        }
    }

    /**
     * A warning about node-local backup storage, or null when there is nothing to say.
     *
     * <p>This directory holds full database dumps on the local filesystem. On a single replica that
     * is fine. Behind a load balancer it is not: a dump written by one node is invisible to the
     * others, so the console shows a shrinking, request-dependent list and a restore fails at the
     * moment it is needed most. Reported only with POSITIVE evidence of another live replica, so a
     * normal single-node install stays quiet.
     */
    public String storageWarning() {
        if (unavailableReason() != null) return null;
        List<String> nodes = replicas.activeNodes();
        if (nodes.size() < 2) return null;

        List<String> unseen = new ArrayList<>();
        for (String node : nodes) {
            if (!Files.exists(Path.of(directory).resolve(NODE_MARKER_PREFIX + node))) {
                unseen.add(node);
            }
        }
        if (unseen.isEmpty()) return null;   // every replica's marker is here: shared storage

        return "This Control Center is running on " + nodes.size() + " replicas, but the backup "
             + "directory is local to this one — no trace of " + String.join(", ", unseen) + ". "
             + "Backups taken on those replicas will not appear here and cannot be restored from "
             + "here. Point controlcenter.database.backup.directory at shared storage (EFS/NFS), or "
             + "run backups against a single replica.";
    }

    private static String nodeName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName()
                .replaceAll("[^A-Za-z0-9._-]", "-");
        } catch (Exception e) {
            return "unknown";
        }
    }

    public List<BackupFile> list() {
        if (unavailableReason() != null) return List.of();
        Path dir = Path.of(directory);
        if (!Files.isDirectory(dir)) return List.of();
        List<BackupFile> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.dump")) {
            for (Path p : stream) {
                if (p.getFileName().toString().startsWith(NODE_MARKER_PREFIX)) continue;
                var attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                String created = LocalDateTime.ofInstant(
                    attrs.creationTime().toInstant(), java.time.ZoneId.systemDefault()).toString();
                out.add(new BackupFile(p.getFileName().toString(), p.getFileName().toString(),
                        attrs.size(), "COMPLETED", created, created));
            }
        } catch (IOException e) {
            log.warn("Could not list backups in {}: {}", directory, e.getMessage());
        }
        out.sort(Comparator.comparing(BackupFile::createdAt).reversed());
        return out;
    }

    public BackupFile create(String label) {
        requireAvailable();
        Path dir = Path.of(directory);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ControlCenterException("Could not create the backup directory: " + e.getMessage(),
                "BACKUP_DIR_UNWRITABLE", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String safeLabel = (label == null || label.isBlank() ? "manual" : label)
            .replaceAll("[^A-Za-z0-9._-]", "-");
        Path target = dir.resolve("cc-" + stamp + "-" + safeLabel + ".dump");

        JdbcTarget t = parseJdbc();
        // -Fc (custom format) so pg_restore can run it; --no-owner keeps it restorable into a
        // differently-owned database, which is the usual disaster-recovery case.
        List<String> cmd = List.of(pgDumpPath, "-Fc", "--no-owner",
            "-h", t.host(), "-p", String.valueOf(t.port()), "-U", dbUsername,
            "-d", t.database(), "-f", target.toString());

        run(cmd, "pg_dump");
        try {
            // Dumps contain every secret this database holds; do not leave them group/world readable.
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX filesystem: the directory's own permissions are the control.
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            size = 0;
        }
        String now = LocalDateTime.now().toString();
        log.info("Control Center database backup written: {} ({} bytes)", target.getFileName(), size);
        return new BackupFile(target.getFileName().toString(), target.getFileName().toString(),
                              size, "COMPLETED", now, now);
    }

    /**
     * Restore a dump over the live control-plane database.
     *
     * <p>Deliberately hard to reach: it needs {@code restoreEnabled}, SUPER_ADMIN at the controller,
     * and a typed confirmation. {@code --clean --if-exists} means the current contents are dropped —
     * there is no partial-restore mode that would leave a half-old, half-new control plane.
     */
    public void restore(String id) {
        requireAvailable();
        if (!restoreEnabled) {
            throw new ControlCenterException(
                "Restore is disabled. It overwrites the live Control Center database, so it must be "
              + "enabled deliberately with controlcenter.database.backup.restoreEnabled=true.",
                "RESTORE_DISABLED", HttpStatus.SERVICE_UNAVAILABLE);
        }
        Path file = resolve(id);
        JdbcTarget t = parseJdbc();
        List<String> cmd = List.of(pgRestorePath, "--clean", "--if-exists", "--no-owner",
            "-h", t.host(), "-p", String.valueOf(t.port()), "-U", dbUsername,
            "-d", t.database(), file.toString());

        log.warn("RESTORING the Control Center database from {} — current contents are being replaced",
                 file.getFileName());
        run(cmd, "pg_restore");
        log.warn("Control Center database restore from {} completed", file.getFileName());
    }

    public Path resolve(String id) {
        requireAvailable();
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new ControlCenterException("That is not a valid backup id.",
                "BACKUP_NOT_FOUND", HttpStatus.BAD_REQUEST);
        }
        Path dir = Path.of(directory).toAbsolutePath().normalize();
        Path file = dir.resolve(id).normalize();
        // Belt and braces with SAFE_ID: the resolved path must still be inside the directory.
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            // On a multi-node console the overwhelmingly likely cause is that the dump lives on
            // another replica's disk, not that it never existed. Saying so beats a bare 404 when
            // the operator is mid-disaster-recovery.
            String warning = storageWarning();
            throw new ControlCenterException(
                warning == null ? "Backup not found: " + id
                                : "Backup not found on this replica: " + id + ". " + warning,
                "BACKUP_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        return file;
    }

    public void delete(String id) {
        Path file = resolve(id);
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new ControlCenterException("Could not delete that backup: " + e.getMessage(),
                "BACKUP_DELETE_FAILED", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── Process plumbing ────────────────────────────────────────────────────

    private void run(List<String> cmd, String what) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // The password goes in the environment, never on the command line where `ps` would show it.
        pb.environment().put("PGPASSWORD", dbPassword == null ? "" : dbPassword);
        pb.redirectErrorStream(true);
        Process proc = null;
        try {
            proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(),
                                       java.nio.charset.StandardCharsets.UTF_8);
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new ControlCenterException(what + " timed out after " + timeoutSeconds + "s.",
                    "BACKUP_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
            }
            if (proc.exitValue() != 0) {
                log.error("{} failed (exit {}): {}", what, proc.exitValue(), tail(output));
                throw new ControlCenterException(
                    what + " failed: " + tail(output), "BACKUP_FAILED", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } catch (ControlCenterException e) {
            throw e;
        } catch (IOException e) {
            throw new ControlCenterException(
                "Could not run " + what + " — is it installed and on PATH? (" + e.getMessage() + ")",
                "BACKUP_TOOL_MISSING", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlCenterException(what + " was interrupted.",
                "BACKUP_INTERRUPTED", HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
        }
    }

    private void requireAvailable() {
        String reason = unavailableReason();
        if (reason != null) {
            throw new ControlCenterException(reason, "BACKUP_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private record JdbcTarget(String host, int port, String database) {}

    /** Pull host/port/db out of the Spring datasource URL rather than duplicating them in config. */
    private JdbcTarget parseJdbc() {
        try {
            String u = jdbcUrl.substring("jdbc:".length());
            java.net.URI uri = java.net.URI.create(u);
            String host = uri.getHost() == null ? "localhost" : uri.getHost();
            int port = uri.getPort() <= 0 ? 5432 : uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
            if (path.isBlank()) throw new IllegalArgumentException("no database in URL");
            return new JdbcTarget(host, port, path);
        } catch (RuntimeException e) {
            throw new ControlCenterException(
                "Could not read the database host/name from spring.datasource.url.",
                "BACKUP_DATASOURCE_UNPARSEABLE", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static String tail(String s) {
        if (s == null) return "";
        String trimmed = s.strip();
        return trimmed.length() <= 600 ? trimmed : trimmed.substring(trimmed.length() - 600);
    }
}
