package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.*;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillingService {

    private final InvoiceRepository invoiceRepo;
    private final InvoiceLineItemRepository lineItemRepo;
    private final ServiceUsageRepository usageRepo;
    private final SharedServiceRepository serviceRepo;
    private final OrganizationRepository orgRepo;
    private final JavaMailSender mailSender;
    private final BackupService backupService;

    @Value("${controlcenter.billing.taxRate:0.15}")
    private BigDecimal taxRate;

    @Value("${controlcenter.billing.invoiceDueDays:30}")
    private int dueDays;

    @Value("${controlcenter.billing.currencyDefault:USD}")
    private String defaultCurrency;

    /** Self-proxy: a direct call to generateInvoice from the scheduled loop would bypass the
     *  transactional proxy, letting a half-written invoice (header without its lines) commit. */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private BillingService self;

    // Auto-generate invoices on 1st of each month at 02:00
    @Scheduled(cron = "0 0 2 1 * *")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "monthlyInvoices", lockAtMostFor = "PT30M")
    public void autoGenerateMonthlyInvoices() {
        LocalDate lastMonthStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        LocalDate lastMonthEnd = lastMonthStart.plusMonths(1).minusDays(1);
        log.info("Auto-generating invoices for period {} to {}", lastMonthStart, lastMonthEnd);
        orgRepo.findAll().forEach(org -> {
            try {
                self.generateInvoice(org.getId(), lastMonthStart, lastMonthEnd, "SYSTEM");
            } catch (Exception e) {
                log.warn("Failed to generate invoice for org {}: {}", org.getId(), e.getMessage());
            }
        });
    }

    @Transactional
    public Invoice generateInvoice(UUID orgId, LocalDate periodStart, LocalDate periodEnd, String generatedBy) {
        Organization org = orgRepo.findById(orgId)
            .orElseThrow(() -> new ControlCenterException("Organization not found: " + orgId));

        List<ServiceUsage> usages = usageRepo.findByOrganizationIdAndPeriodStartBetween(
            orgId, periodStart, periodEnd);

        // Managed-backup subscription charge (base + stored GiB) for this org, if any.
        BackupService.BackupChargeLine backup = backupService.monthlyChargeLine(orgId);

        if (usages.isEmpty() && backup == null) {
            throw new ControlCenterException("No usage found for billing period");
        }

        // The backup amount is denominated in the plan's currency; the invoice is in defaultCurrency.
        // Summing across currencies would silently corrupt the total, so refuse rather than mis-bill.
        if (backup != null && backup.currency() != null
                && !defaultCurrency.equalsIgnoreCase(backup.currency())) {
            throw new ControlCenterException(
                    "Backup plan currency (" + backup.currency() + ") does not match the invoice currency ("
                            + defaultCurrency + ") for org " + orgId + "; align the plan currency before billing.",
                    "BACKUP_CURRENCY_MISMATCH", HttpStatus.CONFLICT);
        }

        // Sum the ALREADY-ROUNDED per-line amounts so the header foots to its own line items by
        // construction. Summing raw 4dp costs and letting the DB round the total silently breaks
        // Σ(lines) == subtotal by a cent (sum-of-rounds vs round-of-sums).
        BigDecimal subtotal = usages.stream()
            .map(u -> u.getCostUsd().setScale(2, RoundingMode.HALF_UP))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .add(backup != null ? backup.totalPrice() : BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal taxAmount = subtotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(taxAmount);

        Invoice invoice = invoiceRepo.save(Invoice.builder()
            .organizationId(orgId)
            .invoiceNumber(generateInvoiceNumber())
            .periodStart(periodStart)
            .periodEnd(periodEnd)
            .subtotal(subtotal)
            .taxRate(taxRate)
            .taxAmount(taxAmount)
            .totalAmount(total)
            .currency(defaultCurrency)
            .status(Invoice.Status.DRAFT)
            .dueDate(LocalDate.now().plusDays(dueDays))
            .generatedBy(generatedBy)
            .build());

        // Create line items per service — fail if any referenced service is missing
        for (ServiceUsage usage : usages) {
            var svc = serviceRepo.findById(usage.getServiceId())
                .orElseThrow(() -> new ControlCenterException("Service not found: " + usage.getServiceId()));
            lineItemRepo.save(InvoiceLineItem.builder()
                .invoiceId(invoice.getId())
                .description(svc.getName() + " API calls")
                .quantity(usage.getCallCount())
                .unitPrice(svc.getPricePerCall())
                .totalPrice(usage.getCostUsd().setScale(2, RoundingMode.HALF_UP))
                .serviceId(svc.getId())
                .build());
        }

        // Managed-backup line item (base subscription + metered storage).
        if (backup != null) {
            lineItemRepo.save(InvoiceLineItem.builder()
                .invoiceId(invoice.getId())
                .description(backup.description())
                .quantity(backup.quantity())
                .unitPrice(backup.unitPrice())
                .totalPrice(backup.totalPrice())
                .build());
        }

        return invoice;
    }

    public void sendInvoice(UUID invoiceId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
            .orElseThrow(() -> new ControlCenterException("Invoice not found"));

        Organization org = orgRepo.findById(invoice.getOrganizationId())
            .orElseThrow(() -> new ControlCenterException("Organization not found"));

        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true);
            helper.setTo(org.getContactEmail());
            helper.setSubject("ZGATE Invoice " + invoice.getInvoiceNumber() + " for " + org.getName());
            helper.setText(buildInvoiceHtml(invoice, org), true);
            mailSender.send(message);

            invoice.setStatus(Invoice.Status.SENT);
            invoice.setSentAt(LocalDateTime.now());
            invoiceRepo.save(invoice);
        } catch (Exception e) {
            log.error("Failed to send invoice {}: {}", invoiceId, e.getMessage());
            throw new ControlCenterException("Failed to send invoice: " + e.getMessage());
        }
    }

    /**
     * Mark an invoice paid. For a SUBSCRIPTION invoice this is the moment the money buys something:
     * the org's paid-through date moves to the invoice's period end, which is what keeps the license
     * renewal job issuing licenses (the kill switch stays open because the customer actually paid).
     *
     * <p>{@code max(current, periodEnd)} — a late-paid older invoice must never PULL BACK a
     * paid-through date a newer payment already advanced.
     */
    @Transactional
    public Invoice markPaid(UUID invoiceId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
            .orElseThrow(() -> new ControlCenterException("Invoice not found"));
        if (invoice.getStatus() == Invoice.Status.CANCELLED) {
            throw new ControlCenterException("A cancelled invoice cannot be marked paid.",
                "INVOICE_CANCELLED", HttpStatus.CONFLICT);
        }
        // Idempotent: paying twice must not smear paidAt (the original payment timestamp is the
        // audit fact) and must not re-run the extension.
        if (invoice.getStatus() == Invoice.Status.PAID) return invoice;

        invoice.setStatus(Invoice.Status.PAID);
        invoice.setPaidAt(LocalDateTime.now());
        invoice = invoiceRepo.save(invoice);

        if (invoice.getType() == Invoice.Type.SUBSCRIPTION) {
            // GREATEST in the database, not a Java max(): two concurrent payments doing
            // read-compare-write are last-writer-wins and can move the paid-through date BACKWARDS
            // — which the license renewal job then reads as a lapse (the kill switch).
            LocalDateTime paidThrough = invoice.getPeriodEnd().plusDays(1).atStartOfDay();
            int updated = orgRepo.advanceSubscriptionPaidThrough(invoice.getOrganizationId(), paidThrough);
            if (updated == 0) {
                throw new ControlCenterException("Organization not found");
            }
            log.info("Org {} subscription advanced to (at least) {} by invoice {}",
                     invoice.getOrganizationId(), paidThrough, invoice.getInvoiceNumber());
        }
        return invoice;
    }

    /**
     * Cancel an unpaid invoice. This is the escape hatch for a mis-raised renewal: an open
     * SUBSCRIPTION invoice blocks future renewals for its period (the double-billing guard), so
     * without cancel a wrong one could only be cleared by marking it paid or database surgery.
     */
    @Transactional
    public Invoice cancel(UUID invoiceId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
            .orElseThrow(() -> new ControlCenterException("Invoice not found"));
        if (invoice.getStatus() == Invoice.Status.PAID) {
            throw new ControlCenterException(
                "A paid invoice cannot be cancelled — issue a correction/credit instead.",
                "INVOICE_ALREADY_PAID", HttpStatus.CONFLICT);
        }
        invoice.setStatus(Invoice.Status.CANCELLED);
        return invoiceRepo.save(invoice);
    }

    /** Real per-org billing accounts (email/country from the org; cost + outstanding computed). */
    public java.util.List<BillingAccount> getAllAccounts() {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        LocalDate today = LocalDate.now();
        java.util.List<BillingAccount> out = new java.util.ArrayList<>();
        for (Organization org : orgRepo.findAll()) {
            BigDecimal cost = usageRepo.sumCostByOrgAndPeriod(org.getId(), monthStart, today);
            if (cost == null) cost = BigDecimal.ZERO;
            BigDecimal outstanding = invoiceRepo.findByOrganizationIdOrderByCreatedAtDesc(org.getId()).stream()
                .filter(i -> i.getStatus() == Invoice.Status.SENT || i.getStatus() == Invoice.Status.OVERDUE)
                .map(Invoice::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new BillingAccount(org.getId(), org.getName(), org.getContactEmail(), org.getCountry(),
                cost, outstanding));
        }
        return out;
    }

    public record BillingAccount(UUID organizationId, String organizationName, String billingEmail,
                                 String country, BigDecimal currentMonthEstimateUsd,
                                 BigDecimal outstandingBalanceUsd) {}

    public BillingDashboard getDashboard(UUID orgId) {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        BigDecimal currentMonthCost = usageRepo.sumCostByOrgAndPeriod(orgId, monthStart, LocalDate.now());
        if (currentMonthCost == null) currentMonthCost = BigDecimal.ZERO;

        List<Invoice> invoices = invoiceRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        BigDecimal outstanding = invoices.stream()
            .filter(i -> i.getStatus() == Invoice.Status.SENT || i.getStatus() == Invoice.Status.OVERDUE)
            .map(Invoice::getTotalAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Invoice lastInvoice = invoices.isEmpty() ? null : invoices.get(0);
        return new BillingDashboard(currentMonthCost, outstanding, lastInvoice, invoices);
    }

    private String generateInvoiceNumber() {
        String prefix = "ZGN-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        long count = invoiceRepo.count() + 1;
        // count()+1 collides under concurrency (two generates racing, or racing a scheduled job);
        // walk forward until free rather than failing the unique constraint and losing the invoice.
        String candidate = prefix + "-" + String.format("%04d", count);
        while (invoiceRepo.existsByInvoiceNumber(candidate)) {
            candidate = prefix + "-" + String.format("%04d", ++count);
        }
        return candidate;
    }

    private String buildInvoiceHtml(Invoice inv, Organization org) {
        return """
            <html><body>
            <h2>ZGATE Invoice %s</h2>
            <p>Dear %s,</p>
            <p>Please find your invoice for the period %s to %s.</p>
            <table border="1" cellpadding="8">
              <tr><td><b>Subtotal</b></td><td>%s %s</td></tr>
              <tr><td><b>Tax (%s%%)</b></td><td>%s %s</td></tr>
              <tr><td><b>Total Due</b></td><td><b>%s %s</b></td></tr>
            </table>
            <p>Due date: %s</p>
            <p>ZGATE Control Center</p>
            </body></html>
            """.formatted(
                inv.getInvoiceNumber(), org.getName(),
                inv.getPeriodStart(), inv.getPeriodEnd(),
                inv.getSubtotal(), inv.getCurrency(),
                inv.getTaxRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString(),
                inv.getTaxAmount(), inv.getCurrency(),
                inv.getTotalAmount(), inv.getCurrency(),
                inv.getDueDate()
        );
    }

    public String invoiceToCsv(Invoice invoice) {
        StringBuilder sb = new StringBuilder();
        sb.append("Invoice Number,Organization,Status,Total (USD),Tax (USD),Period Start,Period End,Due Date,Created\n");
        sb.append(String.join(",",
            invoice.getInvoiceNumber(),
            invoice.getOrganizationId().toString(),
            invoice.getStatus().name(),
            invoice.getTotalAmount().toString(),
            invoice.getTaxAmount().toString(),
            invoice.getPeriodStart() != null ? invoice.getPeriodStart().toString() : "",
            invoice.getPeriodEnd() != null ? invoice.getPeriodEnd().toString() : "",
            invoice.getDueDate() != null ? invoice.getDueDate().toString() : "",
            invoice.getCreatedAt() != null ? invoice.getCreatedAt().toString() : ""
        ));
        return sb.toString();
    }

    public String exportInvoicesCsv() {
        List<Invoice> all = invoiceRepo.findAll();
        StringBuilder sb = new StringBuilder();
        sb.append("Invoice Number,Organization,Status,Total (USD),Tax (USD),Period Start,Period End,Due Date,Created\n");
        for (Invoice inv : all) {
            sb.append(String.join(",",
                inv.getInvoiceNumber(),
                inv.getOrganizationId().toString(),
                inv.getStatus().name(),
                inv.getTotalAmount().toString(),
                inv.getTaxAmount().toString(),
                inv.getPeriodStart() != null ? inv.getPeriodStart().toString() : "",
                inv.getPeriodEnd() != null ? inv.getPeriodEnd().toString() : "",
                inv.getDueDate() != null ? inv.getDueDate().toString() : "",
                inv.getCreatedAt() != null ? inv.getCreatedAt().toString() : ""
            )).append("\n");
        }
        return sb.toString();
    }

    public record BillingDashboard(BigDecimal currentMonthCost, BigDecimal outstanding,
                                    Invoice lastInvoice, List<Invoice> invoices) {}
}
