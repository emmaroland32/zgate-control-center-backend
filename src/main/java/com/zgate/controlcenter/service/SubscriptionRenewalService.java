package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Invoice;
import com.zgate.controlcenter.domain.InvoiceLineItem;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.repository.InvoiceLineItemRepository;
import com.zgate.controlcenter.repository.InvoiceRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Closes the commercial loop the kill switch left open. The license machinery stops renewing when
 * {@code subscriptionValidUntil} lapses — but nothing ever ASKED the customer for money before that
 * happened. This job raises a SUBSCRIPTION renewal invoice ahead of the lapse for every org with a
 * price on file; {@link BillingService#markPaid} then extends the paid-through date when it's paid.
 *
 * <p>Scope rules:
 * <ul>
 *   <li>no {@code subscriptionValidUntil} → unmanaged/perpetual org, never invoiced here;</li>
 *   <li>no {@code subscriptionMonthlyFee} → the deal is billed out of band, skip;</li>
 *   <li>an open (DRAFT/SENT/OVERDUE) renewal invoice already covering the period → skip, never
 *       double-bill.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionRenewalService {

    private final OrganizationRepository orgRepo;
    private final InvoiceRepository invoiceRepo;
    private final InvoiceLineItemRepository lineItemRepo;

    @Value("${controlcenter.billing.renewal.enabled:true}")
    private boolean enabled;

    /** How far before the lapse the renewal invoice is raised. */
    @Value("${controlcenter.billing.renewal.leadDays:30}")
    private int leadDays;

    /** How many months each renewal covers. */
    @Value("${controlcenter.billing.renewal.termMonths:12}")
    private int termMonths;

    @Value("${controlcenter.billing.taxRate:0.15}")
    private BigDecimal taxRate;

    @Value("${controlcenter.billing.invoiceDueDays:30}")
    private int dueDays;

    @Value("${controlcenter.billing.currencyDefault:USD}")
    private String defaultCurrency;

    /** Self-proxy so each org's invoice runs in ITS OWN transaction (see raiseDueRenewalInvoices). */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private SubscriptionRenewalService self;

    /**
     * NOT itself transactional: one wrapping transaction plus a per-org catch is a trap — a
     * flush-time failure marks the tx rollback-only, later orgs "succeed" in the log, and the
     * commit then rolls back every invoice raised that night. Each org instead runs in its own
     * REQUIRES_NEW transaction via the injected self-proxy (never self-invoked directly —
     * the annotation would be silently ignored).
     */
    @Scheduled(cron = "${controlcenter.billing.renewal.cron:0 0 3 * * *}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "subscriptionRenewal", lockAtMostFor = "PT30M")
    public void raiseDueRenewalInvoices() {
        if (!enabled) return;
        LocalDateTime horizon = LocalDateTime.now().plusDays(leadDays);
        for (Organization org : orgRepo.findAll()) {
            try {
                if (org.getSubscriptionValidUntil() == null) continue;
                if (org.getSubscriptionMonthlyFee() == null
                        || org.getSubscriptionMonthlyFee().signum() <= 0) continue;
                if (org.getSubscriptionValidUntil().isAfter(horizon)) continue;
                self.raiseFor(org.getId());
            } catch (Exception e) {
                log.warn("Renewal invoice for org {} failed: {}", org.getSlug(), e.getMessage());
            }
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void raiseFor(UUID orgId) {
        // Re-read inside this transaction: a payment landing mid-sweep advances validUntil, and
        // billing from the sweep's stale snapshot would re-invoice coverage that was just bought.
        Organization org = orgRepo.findById(orgId).orElse(null);
        if (org == null || org.getSubscriptionValidUntil() == null
                || org.getSubscriptionMonthlyFee() == null) return;

        // The new term starts where the paid one ends — even when raised late, the customer is
        // billed for continuous coverage, not from "whenever the job happened to run".
        LocalDate periodStart = org.getSubscriptionValidUntil().toLocalDate();
        LocalDate periodEnd = periodStart.plusMonths(termMonths).minusDays(1);

        // True interval overlap, INCLUDING PAID: a paid invoice covering any part of the new
        // period means that coverage was already bought — an operator pulling validUntil back
        // (chargeback, stale form) must never cause the same period to be billed twice. CANCELLED
        // is the one status that does not block: a cancelled invoice bought nothing.
        boolean alreadyRaised = invoiceRepo.findByOrganizationIdAndTypeAndStatusIn(
                org.getId(), Invoice.Type.SUBSCRIPTION,
                List.of(Invoice.Status.DRAFT, Invoice.Status.SENT,
                        Invoice.Status.OVERDUE, Invoice.Status.PAID)).stream()
            .anyMatch(i -> !i.getPeriodStart().isAfter(periodEnd)
                        && !i.getPeriodEnd().isBefore(periodStart));
        if (alreadyRaised) return;

        BigDecimal subtotal = org.getSubscriptionMonthlyFee()
            .multiply(BigDecimal.valueOf(termMonths)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxAmount = subtotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);

        Invoice invoice = invoiceRepo.save(Invoice.builder()
            .organizationId(org.getId())
            .invoiceNumber(renewalInvoiceNumber())
            .type(Invoice.Type.SUBSCRIPTION)
            .periodStart(periodStart)
            .periodEnd(periodEnd)
            .subtotal(subtotal)
            .taxRate(taxRate)
            .taxAmount(taxAmount)
            .totalAmount(subtotal.add(taxAmount))
            .currency(defaultCurrency)
            .status(Invoice.Status.DRAFT)
            .dueDate(LocalDate.now().plusDays(dueDays))
            .notes("Platform subscription renewal " + periodStart + " to " + periodEnd
                 + ". Payment extends the subscription through " + periodEnd + ".")
            .generatedBy("SYSTEM")
            .build());

        lineItemRepo.save(InvoiceLineItem.builder()
            .invoiceId(invoice.getId())
            .description("ZGATE platform subscription, " + periodStart + " to " + periodEnd
                       + " (" + termMonths + " months)")
            .quantity((long) termMonths)
            .unitPrice(org.getSubscriptionMonthlyFee())
            .totalPrice(subtotal)
            .build());

        log.info("Renewal invoice {} raised for org {} covering {} to {}",
                 invoice.getInvoiceNumber(), org.getSlug(), periodStart, periodEnd);
    }

    private String renewalInvoiceNumber() {
        String prefix = "ZGS-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        long count = invoiceRepo.count() + 1;
        String candidate = prefix + "-" + String.format("%04d", count);
        while (invoiceRepo.existsByInvoiceNumber(candidate)) {
            candidate = prefix + "-" + String.format("%04d", ++count);
        }
        return candidate;
    }
}
