package com.zgate.controlcenter.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Whether the control-plane backup directory is shared across replicas.
 *
 * <p>This matters because the failure is silent and badly timed: behind a load balancer, a dump
 * written by one replica is invisible to the others, so the console shows a shrinking,
 * request-dependent list — and the operator finds out at the moment they need a restore. The
 * warning has to fire on a genuinely unshared directory and stay quiet otherwise, or it gets
 * ignored.
 */
class BackupStorageWarningTest {

    private ControlCenterBackupService service(Path dir, ReplicaRegistry replicas) {
        ControlCenterBackupService svc = new ControlCenterBackupService(replicas);
        ReflectionTestUtils.setField(svc, "directory", dir.toString());
        return svc;
    }

    private ReplicaRegistry replicas(String... nodes) {
        ReplicaRegistry r = mock(ReplicaRegistry.class);
        when(r.activeNodes()).thenReturn(List.of(nodes));
        return r;
    }

    private void marker(Path dir, String node) throws IOException {
        Files.writeString(dir.resolve(".cc-node-" + node), "seen");
    }

    @Test
    @DisplayName("a single-replica console says nothing — the common case must stay quiet")
    void silentOnSingleNode(@TempDir Path dir) {
        assertThat(service(dir, replicas("node-a")).storageWarning()).isNull();
    }

    @Test
    @DisplayName("no evidence at all is treated as single-node, not as a problem")
    void silentWhenNoEvidence(@TempDir Path dir) {
        // A fresh install whose scheduled jobs have not run yet. Warning here would cry wolf on
        // every new deployment and train operators to ignore it.
        assertThat(service(dir, replicas()).storageWarning()).isNull();
    }

    @Test
    @DisplayName("several replicas with every marker present means shared storage — still quiet")
    void silentWhenDirectoryIsShared(@TempDir Path dir) throws IOException {
        marker(dir, "node-a");
        marker(dir, "node-b");
        assertThat(service(dir, replicas("node-a", "node-b")).storageWarning()).isNull();
    }

    @Test
    @DisplayName("several replicas but only our own marker: warn, and name the missing replica")
    void warnsWhenDirectoryIsNodeLocal(@TempDir Path dir) throws IOException {
        marker(dir, "node-a");
        String warning = service(dir, replicas("node-a", "node-b")).storageWarning();
        assertThat(warning)
            .isNotNull()
            .contains("node-b")                 // which replica we cannot see
            .contains("cannot be restored")     // the consequence, not just the symptom
            .contains("shared storage");        // what to do about it
        assertThat(warning).doesNotContain("node-a");
    }

    @Test
    @DisplayName("backups are unconfigured: no warning, because there is nothing to lose yet")
    void silentWhenBackupsAreOff(@TempDir Path dir) {
        ControlCenterBackupService svc = service(dir, replicas("node-a", "node-b"));
        ReflectionTestUtils.setField(svc, "directory", "");
        assertThat(svc.storageWarning()).isNull();
    }

    @Test
    @DisplayName("node markers never appear in the backup listing")
    void markersAreNotListedAsBackups(@TempDir Path dir) throws IOException {
        marker(dir, "node-a");
        Files.writeString(dir.resolve("cc-20260803-120000-manual.dump"), "x");
        var listed = service(dir, replicas("node-a")).list();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).id()).isEqualTo("cc-20260803-120000-manual.dump");
    }

    @Test
    @DisplayName("a dump missing on this replica explains why, instead of a bare 'not found'")
    void missingDumpExplainsTheLikelyCause(@TempDir Path dir) throws IOException {
        // Mid-disaster-recovery, "Backup not found" reads as data loss. On a multi-node console
        // the overwhelmingly likely cause is that it is sitting on another replica's disk.
        marker(dir, "node-a");
        ControlCenterBackupService svc = service(dir, replicas("node-a", "node-b"));
        assertThatThrownBySafely(() -> svc.resolve("cc-20260803-120000-manual.dump"));
    }

    private void assertThatThrownBySafely(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected the missing dump to be refused");
        } catch (com.zgate.controlcenter.exception.ControlCenterException e) {
            assertThat(e.getMessage())
                .contains("not found on this replica")
                .contains("node-b");
        }
    }
}
