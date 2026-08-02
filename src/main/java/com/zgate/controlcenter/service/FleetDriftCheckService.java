package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.InfrastructureStack;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.InfrastructureStackRepository;
import com.zgate.controlcenter.service.provisioning.ProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Scheduled drift detection across the fleet. Until now drift was only found when an operator
 * happened to press Refresh on a stack; hand-edited customer infrastructure could sit divergent for
 * months. This sweep refreshes the least-recently-checked ACTIVE stacks on a nightly cron.
 *
 * <p><b>Off by default</b> ({@code controlcenter.fleet.driftCheck.enabled=false}): every refresh is
 * a real terraform run against a customer's cloud — an operator should decide the fleet is ready
 * for that. The per-run cap exists because each refresh spawns a runner container; a 200-stack
 * fleet must rotate through nights, not launch 200 containers at 04:00.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FleetDriftCheckService {

    private final InfrastructureStackRepository stackRepo;
    private final ProvisioningService provisioningService;

    @Value("${controlcenter.fleet.driftCheck.enabled:false}")
    private boolean enabled;

    @Value("${controlcenter.fleet.driftCheck.maxPerRun:5}")
    private int maxPerRun;

    @Scheduled(cron = "${controlcenter.fleet.driftCheck.cron:0 0 4 * * *}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "fleetDriftCheck", lockAtMostFor = "PT30M")
    public void sweep() {
        if (!enabled) return;

        List<InfrastructureStack> due = stackRepo.findByStatus(InfrastructureStack.Status.ACTIVE).stream()
            .filter(s -> s.getLastAppliedAt() != null)
            .sorted(Comparator.comparing(InfrastructureStack::getLastDriftCheckAt,
                                         Comparator.nullsFirst(Comparator.naturalOrder())))
            .limit(Math.max(1, maxPerRun))
            .toList();

        for (InfrastructureStack stack : due) {
            try {
                provisioningService.refresh(stack.getId(), "fleet-drift-check");
                log.info("Drift check queued for stack {} (last checked {})",
                         stack.getId(), stack.getLastDriftCheckAt());
            } catch (ControlCenterException e) {
                // Busy stacks and an unconfigured runner are normal states, not failures.
                log.debug("Drift check skipped for stack {}: {}", stack.getId(), e.getMessage());
            }
        }
        if (!due.isEmpty()) {
            log.info("Fleet drift check: {} stack(s) queued at {}", due.size(), LocalDateTime.now());
        }
    }
}
