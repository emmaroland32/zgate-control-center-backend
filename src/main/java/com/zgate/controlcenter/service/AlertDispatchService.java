package com.zgate.controlcenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.domain.Alert;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.domain.Webhook;
import com.zgate.controlcenter.domain.WebhookDelivery;
import com.zgate.controlcenter.repository.AlertRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.WebhookDeliveryRepository;
import com.zgate.controlcenter.repository.WebhookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers fired alerts to humans. An alert row in a table nobody is watching is not monitoring —
 * this sweep pushes every FIRING alert to the configured operator emails and to every enabled
 * webhook whose event list covers alerts, recording a {@link WebhookDelivery} row per attempt
 * (which also makes the webhook registry real for the first time: nothing ever delivered before).
 *
 * <p>Delivery is at-least-once with bounded retry: an alert is marked {@code notifiedAt} as soon as
 * ANY channel accepts it; total failure increments {@code notifyAttempts} and the next sweep tries
 * again, giving up quietly after {@code maxAttempts} so a dead SMTP server cannot pile up work
 * forever. Webhook payloads are unsigned — the registry stores only a hash of the signing secret,
 * so receivers should rely on the URL being unguessable or on their own header configured in the
 * webhook's extra headers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertDispatchService {

    private static final String EVENT = "ALERT_FIRING";

    private final AlertRepository alertRepo;
    private final WebhookRepository webhookRepo;
    private final WebhookDeliveryRepository deliveryRepo;
    private final OrganizationRepository orgRepo;
    private final JavaMailSender mailSender;
    private final ObjectMapper mapper;
    private final WebhookUrlValidator urlValidator;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${controlcenter.alerts.dispatch.enabled:true}")
    private boolean enabled;

    /** Comma-separated operator inboxes. Blank = no email channel. */
    @Value("${controlcenter.alerts.dispatch.emails:}")
    private String notifyEmails;

    @Value("${controlcenter.alerts.dispatch.maxAttempts:5}")
    private int maxAttempts;

    @Value("${spring.mail.username:}")
    private String mailFrom;

    @Scheduled(fixedDelayString = "${controlcenter.alerts.dispatch.sweepMs:30000}", initialDelay = 20_000)
    @SchedulerLock(name = "alertDispatch", lockAtMostFor = "PT5M")
    public void dispatchPending() {
        if (!enabled) return;
        List<Alert> due = alertRepo
            .findTop50ByStatusAndNotifiedAtNullAndNotifyAttemptsLessThanOrderByFiredAtAsc(
                Alert.Status.FIRING, maxAttempts);
        for (Alert alert : due) {
            boolean delivered = dispatch(alert);
            if (delivered) {
                alert.setNotifiedAt(LocalDateTime.now());
            } else {
                alert.setNotifyAttempts(alert.getNotifyAttempts() + 1);
                if (alert.getNotifyAttempts() >= maxAttempts) {
                    log.error("Alert {} ({}) could not be delivered on any channel after {} attempts",
                              alert.getId(), alert.getTitle(), maxAttempts);
                }
            }
            alertRepo.save(alert);
        }
    }

    private boolean dispatch(Alert alert) {
        String orgName = orgRepo.findById(alert.getOrganizationId())
            .map(Organization::getName).orElse(alert.getOrganizationId().toString());

        boolean any = false;
        any |= sendEmails(alert, orgName);
        any |= sendWebhooks(alert, orgName);

        // No channel configured at all: mark delivered rather than retrying forever —
        // the in-app inbox is then the (explicitly chosen) only channel.
        if (!any && notifyEmails.isBlank() && webhookRepo.findByEnabled(true).isEmpty()) {
            return true;
        }
        return any;
    }

    private boolean sendEmails(Alert alert, String orgName) {
        if (notifyEmails == null || notifyEmails.isBlank()) return false;
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false);
            helper.setTo(notifyEmails.split("\\s*,\\s*"));
            if (!mailFrom.isBlank()) helper.setFrom(mailFrom);
            helper.setSubject("[" + alert.getSeverity() + "] " + alert.getTitle() + " — " + orgName);
            helper.setText(alert.getMessage() + "\n\nFired at: " + alert.getFiredAt()
                         + "\nOrganization: " + orgName
                         + "\n\nAcknowledge in Control Center → Alerts.");
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("Alert {} email delivery failed: {}", alert.getId(), e.getMessage());
            return false;
        }
    }

    /** Fire one webhook with a synthetic event so an operator can prove the endpoint works. */
    public java.util.Map<String, Object> testDeliver(Webhook hook) {
        long start = System.currentTimeMillis();
        try {
            if (!urlValidator.isDeliverable(hook.getUrl())) {
                return java.util.Map.of("ok", false, "detail",
                    "Refused: the URL must be an external https endpoint.");
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(hook.getUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-ZGATE-Event", "TEST")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"event\":\"TEST\",\"message\":\"Control Center webhook test\"}"));
            extraHeaders(hook).forEach(req::header);
            HttpResponse<Void> resp = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
            int ms = (int) (System.currentTimeMillis() - start);
            deliveryRepo.save(WebhookDelivery.builder()
                .webhookId(hook.getId()).event("TEST").payload("manual test")
                .statusCode(resp.statusCode()).durationMs(ms).build());
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            return java.util.Map.of("ok", ok, "statusCode", resp.statusCode(), "durationMs", ms,
                "detail", ok ? "Endpoint accepted the test event."
                             : "Endpoint returned HTTP " + resp.statusCode() + ".");
        } catch (Exception e) {
            return java.util.Map.of("ok", false, "detail",
                "Delivery failed: " + e.getClass().getSimpleName());
        }
    }

    private boolean sendWebhooks(Alert alert, String orgName) {
        boolean any = false;
        for (Webhook hook : webhookRepo.findByEnabled(true)) {
            if (!covers(hook)) continue;
            // Re-check at send time: DNS for a host that validated as external can be re-pointed
            // at an internal address afterwards.
            if (!urlValidator.isDeliverable(hook.getUrl())) continue;
            long start = System.currentTimeMillis();
            Integer status = null;
            String responseBody = null;
            try {
                String payload = payloadJson(alert, orgName);
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(hook.getUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-ZGATE-Event", EVENT)
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
                extraHeaders(hook).forEach(req::header);
                // Discard the body: storing a remote endpoint's response makes the delivery log a
                // read channel for whatever that URL returns (and a stored-XSS sink in the UI).
                // The status code is the only outcome an operator needs.
                HttpResponse<Void> resp = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
                status = resp.statusCode();
                if (status >= 200 && status < 300) any = true;
            } catch (Exception e) {
                responseBody = "delivery failed: " + e.getClass().getSimpleName();
                log.warn("Alert {} webhook {} failed: {}", alert.getId(), hook.getName(), e.getMessage());
            }
            deliveryRepo.save(WebhookDelivery.builder()
                .webhookId(hook.getId())
                .event(EVENT)
                .payload("alertId=" + alert.getId() + " title=" + alert.getTitle())
                .statusCode(status)
                .response(responseBody)
                .durationMs((int) (System.currentTimeMillis() - start))
                .build());
            hook.setLastFiredAt(LocalDateTime.now());
            hook.setLastStatus(status != null && status >= 200 && status < 300 ? "SUCCESS" : "FAILED");
            hook.setFireCount(hook.getFireCount() + 1);
            if (status != null && status >= 200 && status < 300) {
                hook.setSuccessCount(hook.getSuccessCount() + 1);
            }
            webhookRepo.save(hook);
        }
        return any;
    }

    /** A hook covers alerts when its events JSON names ALERT_FIRING or "*" (or is empty = everything). */
    private boolean covers(Webhook hook) {
        String events = hook.getEvents();
        if (events == null || events.isBlank() || "[]".equals(events.trim())) return true;
        return events.contains(EVENT) || events.contains("\"*\"");
    }

    private Map<String, String> extraHeaders(Webhook hook) {
        try {
            if (hook.getHeaders() == null || hook.getHeaders().isBlank()) return Map.of();
            return mapper.readValue(hook.getHeaders(),
                mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String payloadJson(Alert alert, String orgName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", EVENT);
        body.put("alertId", alert.getId());
        body.put("severity", alert.getSeverity());
        body.put("title", alert.getTitle());
        body.put("message", alert.getMessage());
        body.put("organizationId", alert.getOrganizationId());
        body.put("organizationName", orgName);
        body.put("firedAt", alert.getFiredAt() == null ? null : alert.getFiredAt().toString());
        return mapper.writeValueAsString(body);
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
