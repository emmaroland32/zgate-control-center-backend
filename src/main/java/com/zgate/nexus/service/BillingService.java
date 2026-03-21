package com.zgate.nexus.service;

import com.zgate.nexus.domain.*;
import com.zgate.nexus.exception.NexusException;
import com.zgate.nexus.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${nexus.billing.taxRate:0.15}")
    private BigDecimal taxRate;

    @Value("${nexus.billing.invoiceDueDays:30}")
    private int dueDays;

    @Value("${nexus.billing.currencyDefault:USD}")
    private String defaultCurrency;

    // Auto-generate invoices on 1st of each month at 02:00
    @Scheduled(cron = "0 0 2 1 * *")
    public void autoGenerateMonthlyInvoices() {
        LocalDate lastMonthStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        LocalDate lastMonthEnd = lastMonthStart.plusMonths(1).minusDays(1);
        log.info("Auto-generating invoices for period {} to {}", lastMonthStart, lastMonthEnd);
        orgRepo.findAll().forEach(org -> {
            try {
                generateInvoice(org.getId(), lastMonthStart, lastMonthEnd, "SYSTEM");
            } catch (Exception e) {
                log.warn("Failed to generate invoice for org {}: {}", org.getId(), e.getMessage());
            }
        });
    }

    @Transactional
    public Invoice generateInvoice(UUID orgId, LocalDate periodStart, LocalDate periodEnd, String generatedBy) {
        Organization org = orgRepo.findById(orgId)
            .orElseThrow(() -> new NexusException("Organization not found: " + orgId));

        List<ServiceUsage> usages = usageRepo.findByOrganizationIdAndPeriodStartBetween(
            orgId, periodStart, periodEnd);

        if (usages.isEmpty()) {
            throw new NexusException("No usage found for billing period");
        }

        BigDecimal subtotal = usages.stream()
            .map(ServiceUsage::getCostUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

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
                .orElseThrow(() -> new NexusException("Service not found: " + usage.getServiceId()));
            lineItemRepo.save(InvoiceLineItem.builder()
                .invoiceId(invoice.getId())
                .description(svc.getName() + " API calls")
                .quantity(usage.getCallCount())
                .unitPrice(svc.getPricePerCall())
                .totalPrice(usage.getCostUsd().setScale(2, RoundingMode.HALF_UP))
                .serviceId(svc.getId())
                .build());
        }

        return invoice;
    }

    public void sendInvoice(UUID invoiceId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
            .orElseThrow(() -> new NexusException("Invoice not found"));

        Organization org = orgRepo.findById(invoice.getOrganizationId())
            .orElseThrow(() -> new NexusException("Organization not found"));

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
            throw new NexusException("Failed to send invoice: " + e.getMessage());
        }
    }

    public Invoice markPaid(UUID invoiceId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
            .orElseThrow(() -> new NexusException("Invoice not found"));
        invoice.setStatus(Invoice.Status.PAID);
        invoice.setPaidAt(LocalDateTime.now());
        return invoiceRepo.save(invoice);
    }

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
        return prefix + "-" + String.format("%04d", count);
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
            <p>ZGATE Nexus Control Center</p>
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
