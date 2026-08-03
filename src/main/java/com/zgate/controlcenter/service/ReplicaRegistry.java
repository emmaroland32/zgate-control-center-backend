package com.zgate.controlcenter.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Which Control Center replicas are actually running.
 *
 * <p>Derived from ShedLock's own bookkeeping rather than a new mechanism: every scheduled job
 * records the node that last held its lock in {@code shedlock.locked_by}, so the set of distinct
 * recent values IS the set of live replicas. That costs nothing to maintain and cannot drift out of
 * step with reality the way a hand-maintained node list would.
 *
 * <p>It exists because several things in this console are node-local and only break when there is
 * more than one node — most importantly the control-plane backup directory. Knowing whether we are
 * multi-node turns "backups mysteriously disappeared" into a warning the operator can act on
 * BEFORE they need a restore.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplicaRegistry {

    /**
     * How far back a lock still counts as evidence of a live node. Generous on purpose: the longest
     * job interval here is daily, so a node that only ever wins the nightly jobs must not look dead.
     */
    private static final int RECENT_DAYS = 3;

    private final JdbcTemplate jdbc;

    /** Node names seen holding a scheduler lock recently, newest first. Never throws. */
    public List<String> activeNodes() {
        try {
            return jdbc.queryForList(
                "SELECT locked_by FROM shedlock WHERE locked_at > ? "
              + "GROUP BY locked_by ORDER BY MAX(locked_at) DESC",
                String.class, LocalDateTime.now().minusDays(RECENT_DAYS));
        } catch (RuntimeException e) {
            // Missing table, permissions, whatever — this is advisory, never a reason to fail a
            // request that happens to ask about it.
            log.debug("Could not read the replica registry: {}", e.toString());
            return List.of();
        }
    }

    /**
     * True only when we have POSITIVE evidence of more than one replica.
     *
     * <p>Deliberately not the inverse of "single node": a fresh deployment whose scheduled jobs
     * have not run yet reports one node, or none. Treating that as multi-node would cry wolf on
     * every new install, so unknown is reported as single.
     */
    public boolean isMultiNode() {
        return activeNodes().size() > 1;
    }
}
