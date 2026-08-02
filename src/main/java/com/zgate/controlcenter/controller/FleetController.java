package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.domain.FleetRollout;
import com.zgate.controlcenter.domain.FleetRolloutItem;
import com.zgate.controlcenter.service.AuditService;
import com.zgate.controlcenter.service.FleetOverviewService;
import com.zgate.controlcenter.service.FleetRolloutService;
import com.zgate.controlcenter.web.ResponseMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fleet operations: the cross-customer view and staged version rollouts.
 *
 * <p>Reads are open to any operator role (SUPPORT and VIEWER watch rollouts during an incident);
 * anything that changes customer infrastructure is SUPER_ADMIN/ADMIN, matching provisioning.
 */
@RestController
@RequestMapping("/api/v1/fleet")
@RequiredArgsConstructor
public class FleetController {

    private final FleetOverviewService overview;
    private final FleetRolloutService rollouts;
    private final com.zgate.controlcenter.service.SlaService sla;
    private final AuditService audit;
    private final com.zgate.controlcenter.security.ClientIpResolver clientIpResolver;

    // ── Overview ────────────────────────────────────────────────────────────

    @GetMapping("/overview")
    public ResponseEntity<FleetOverviewService.FleetOverview> overview() {
        return ResponseEntity.ok(overview.overview());
    }

    /** Evidence-based per-org uptime over the window (default 30 days), from recorded outages. */
    @GetMapping("/sla")
    public ResponseEntity<List<com.zgate.controlcenter.service.SlaService.OrgSla>> sla(
            @RequestParam(defaultValue = "30") int windowDays) {
        return ResponseEntity.ok(sla.compute(Math.min(windowDays, 365)));
    }

    // ── Rollouts ────────────────────────────────────────────────────────────

    @GetMapping("/rollouts")
    public ResponseEntity<List<FleetRollout>> list() {
        return ResponseEntity.ok(rollouts.findAll());
    }

    @GetMapping("/rollouts/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id) {
        FleetRollout rollout = rollouts.find(id);
        List<FleetRolloutItem> items = rollouts.items(id);
        return ResponseEntity.ok(Map.of("rollout", rollout, "items", items));
    }

    @PostMapping("/rollouts")
    @com.zgate.controlcenter.security.RequiresStepUp
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "ROLLOUT_CREATED",
                     value = "Rollout created. The canary wave starts on the next orchestrator tick.")
    public ResponseEntity<FleetRollout> create(
            @Valid @RequestBody FleetRolloutService.CreateRolloutRequest req,
            @AuthenticationPrincipal UserDetails user,
            HttpServletRequest http) {
        FleetRollout rollout = rollouts.create(req, user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "FLEET_ROLLOUT_CREATED",
                  "FleetRollout", rollout.getId().toString(), null, clientIp(http),
                  "release=" + rollout.getReleaseVersion() + " autoApply=" + rollout.isAutoApply(),
                  AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(rollout);
    }

    /** Four-eyes approval — must come from a different operator than the rollout's creator. */
    @PostMapping("/rollouts/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "ROLLOUT_APPROVED", value = "Rollout approved — it starts on the next tick")
    public ResponseEntity<FleetRollout> approve(@PathVariable UUID id,
                                                @AuthenticationPrincipal UserDetails user,
                                                HttpServletRequest http) {
        FleetRollout r = rollouts.approve(id, user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "FLEET_ROLLOUT_APPROVED",
                  "FleetRollout", id.toString(), null, clientIp(http),
                  "createdBy=" + r.getCreatedBy(), AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(r);
    }

    @PostMapping("/rollouts/{id}/pause")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "ROLLOUT_PAUSED", value = "Rollout paused — no new work will start")
    public ResponseEntity<FleetRollout> pause(@PathVariable UUID id,
                                              @RequestBody(required = false) ReasonBody body,
                                              @AuthenticationPrincipal UserDetails user,
                                              HttpServletRequest http) {
        FleetRollout r = rollouts.pause(id, body == null ? null : body.getReason(), user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "FLEET_ROLLOUT_PAUSED",
                  "FleetRollout", id.toString(), null, clientIp(http), null, AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(r);
    }

    @PostMapping("/rollouts/{id}/resume")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "ROLLOUT_RESUMED",
                     value = "Rollout resumed — failed stacks in the current wave will be retried")
    public ResponseEntity<FleetRollout> resume(@PathVariable UUID id,
                                               @AuthenticationPrincipal UserDetails user,
                                               HttpServletRequest http) {
        FleetRollout r = rollouts.resume(id, user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "FLEET_ROLLOUT_RESUMED",
                  "FleetRollout", id.toString(), null, clientIp(http), null, AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(r);
    }

    @PostMapping("/rollouts/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "ROLLOUT_CANCELLED",
                     value = "Rollout cancelled. In-flight terraform runs finish on their own.")
    public ResponseEntity<FleetRollout> cancel(@PathVariable UUID id,
                                               @AuthenticationPrincipal UserDetails user,
                                               HttpServletRequest http) {
        FleetRollout r = rollouts.cancel(id, user.getUsername());
        audit.log(user.getUsername(), user.getUsername(), "FLEET_ROLLOUT_CANCELLED",
                  "FleetRollout", id.toString(), null, clientIp(http), null, AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(r);
    }

    @Data
    public static class ReasonBody {
        private String reason;
    }

    /** Honours X-Forwarded-For only from a configured proxy hop — see ClientIpResolver. */
    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }
}
