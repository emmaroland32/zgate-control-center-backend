package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.License;
import com.zgate.controlcenter.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Drives the subscription kill switch. On a schedule it finds short-lived licenses approaching expiry
 * and asks {@link LicenseService#renewIfEntitled} to extend each one — which renews only while the
 * org's subscription is valid. Paid orgs stay licensed seamlessly (their hourly bundle poll picks up
 * the extended expiry); lapsed orgs are simply not renewed, so their license expires and the org-side
 * grace window then ends in a lock.
 *
 * <p>Purely additive and safe by default: orgs with no {@code subscriptionValidUntil} are treated as
 * perpetual/unmanaged and are always renewed, so existing installs are never auto-lapsed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LicenseRenewalService {

    private final LicenseRepository licenseRepo;
    private final LicenseService licenseService;

    /** How far ahead of expiry to start renewing, so a paid org never actually lapses. */
    @Value("${controlcenter.license.renewalWindowDays:10}")
    private int renewalWindowDays;

    @Value("${controlcenter.license.autoRenew:true}")
    private boolean autoRenew;

    /** Daily at 02:30 by default. */
    @Scheduled(cron = "${controlcenter.license.renewalCron:0 30 2 * * *}")
    public void renewDueLicenses() {
        if (!autoRenew) {
            log.debug("License auto-renewal disabled");
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().plusDays(renewalWindowDays);
        List<License> due = licenseRepo.findExpiringSoon(cutoff);
        if (due.isEmpty()) return;

        int renewed = 0, unentitled = 0, other = 0;
        for (License l : due) {
            try {
                switch (licenseService.renewIfEntitled(l.getId())) {
                    case RENEWED -> renewed++;
                    case SKIPPED_NOT_ENTITLED -> unentitled++;
                    default -> other++;
                }
            } catch (Exception e) {
                other++;
                log.warn("License renewal errored for {}: {}", l.getId(), e.getMessage());
            }
        }
        log.info("License renewal sweep: {} due → {} renewed, {} refused (unentitled), {} skipped/errored",
            due.size(), renewed, unentitled, other);
    }
}
