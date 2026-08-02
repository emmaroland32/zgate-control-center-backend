package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Invoice;
import com.zgate.controlcenter.domain.InvoiceLineItem;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The money loop: paying a SUBSCRIPTION invoice is what keeps the license kill switch open, so the
 * paid-through extension must be exact (period end, never pulled back) and renewal invoices must
 * never double-bill a period.
 */
class SubscriptionBillingTest {

    private InvoiceRepository invoiceRepo;
    private InvoiceLineItemRepository lineItemRepo;
    private OrganizationRepository orgRepo;
    private BillingService billing;
    private SubscriptionRenewalService renewal;

    private final UUID orgId = UUID.randomUUID();
    private Organization org;

    @BeforeEach
    void setUp() {
        invoiceRepo = mock(InvoiceRepository.class);
        lineItemRepo = mock(InvoiceLineItemRepository.class);
        orgRepo = mock(OrganizationRepository.class);

        billing = new BillingService(invoiceRepo, lineItemRepo,
            mock(ServiceUsageRepository.class), mock(SharedServiceRepository.class),
            orgRepo, mock(JavaMailSender.class), mock(BackupService.class));
        ReflectionTestUtils.setField(billing, "taxRate", new BigDecimal("0.15"));
        ReflectionTestUtils.setField(billing, "dueDays", 30);
        ReflectionTestUtils.setField(billing, "defaultCurrency", "USD");

        renewal = new SubscriptionRenewalService(orgRepo, invoiceRepo, lineItemRepo);
        // In production `self` is the transactional proxy; in a unit test the instance itself is fine.
        ReflectionTestUtils.setField(renewal, "self", renewal);
        ReflectionTestUtils.setField(renewal, "enabled", true);
        ReflectionTestUtils.setField(renewal, "leadDays", 30);
        ReflectionTestUtils.setField(renewal, "termMonths", 12);
        ReflectionTestUtils.setField(renewal, "taxRate", new BigDecimal("0.15"));
        ReflectionTestUtils.setField(renewal, "dueDays", 30);
        ReflectionTestUtils.setField(renewal, "defaultCurrency", "USD");

        org = Organization.builder()
            .id(orgId).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(orgRepo.findAll()).thenReturn(List.of(org));
        when(orgRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceRepo.save(any())).thenAnswer(i -> {
            Invoice inv = i.getArgument(0);
            if (inv.getId() == null) ReflectionTestUtils.setField(inv, "id", UUID.randomUUID());
            return inv;
        });
        when(lineItemRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceRepo.existsByInvoiceNumber(any())).thenReturn(false);
        when(invoiceRepo.findByOrganizationIdAndTypeAndStatusIn(any(), any(), any())).thenReturn(List.of());
    }

    private Invoice invoice(Invoice.Type type, LocalDate periodEnd, Invoice.Status status) {
        Invoice inv = Invoice.builder()
            .id(UUID.randomUUID()).organizationId(orgId)
            .invoiceNumber("TEST-0001").type(type)
            .periodStart(periodEnd.minusMonths(12).plusDays(1)).periodEnd(periodEnd)
            .subtotal(new BigDecimal("1200.00")).taxRate(new BigDecimal("0.15"))
            .taxAmount(new BigDecimal("180.00")).totalAmount(new BigDecimal("1380.00"))
            .currency("USD").status(status)
            .build();
        when(invoiceRepo.findById(inv.getId())).thenReturn(Optional.of(inv));
        return inv;
    }

    @Test
    @DisplayName("paying a SUBSCRIPTION invoice advances paid-through atomically (DB GREATEST, not Java max)")
    void payingSubscriptionInvoiceExtendsPaidThrough() {
        LocalDate periodEnd = LocalDate.now().plusMonths(11);
        Invoice inv = invoice(Invoice.Type.SUBSCRIPTION, periodEnd, Invoice.Status.SENT);
        when(orgRepo.advanceSubscriptionPaidThrough(orgId, periodEnd.plusDays(1).atStartOfDay()))
            .thenReturn(1);

        billing.markPaid(inv.getId());

        // The never-regress guarantee lives in the SQL GREATEST — the service's job is to hand it
        // exactly periodEnd+1 start-of-day and nothing else.
        verify(orgRepo).advanceSubscriptionPaidThrough(orgId, periodEnd.plusDays(1).atStartOfDay());
        verify(orgRepo, never()).save(any());
    }

    @Test
    @DisplayName("marking paid twice is a no-op: paidAt is not smeared, the extension runs once")
    void latePaymentNeverRegresses() {
        LocalDate periodEnd = LocalDate.now().plusMonths(11);
        Invoice inv = invoice(Invoice.Type.SUBSCRIPTION, periodEnd, Invoice.Status.SENT);
        when(orgRepo.advanceSubscriptionPaidThrough(any(), any())).thenReturn(1);

        billing.markPaid(inv.getId());
        LocalDateTime firstPaidAt = inv.getPaidAt();
        billing.markPaid(inv.getId());

        assertThat(inv.getPaidAt()).isEqualTo(firstPaidAt);
        verify(orgRepo, times(1)).advanceSubscriptionPaidThrough(any(), any());
    }

    @Test
    @DisplayName("paying a USAGE invoice never touches the subscription")
    void usageInvoiceDoesNotTouchSubscription() {
        Invoice inv = invoice(Invoice.Type.USAGE, LocalDate.now(), Invoice.Status.SENT);

        billing.markPaid(inv.getId());

        verify(orgRepo, never()).advanceSubscriptionPaidThrough(any(), any());
        verify(orgRepo, never()).save(any());
    }

    @Test
    @DisplayName("cancel clears an unpaid invoice; a paid one is refused")
    void cancelSemantics() {
        Invoice open = invoice(Invoice.Type.SUBSCRIPTION, LocalDate.now().plusMonths(11), Invoice.Status.SENT);
        billing.cancel(open.getId());
        assertThat(open.getStatus()).isEqualTo(Invoice.Status.CANCELLED);

        Invoice paid = invoice(Invoice.Type.SUBSCRIPTION, LocalDate.now().plusMonths(5), Invoice.Status.PAID);
        assertThatThrownBy(() -> billing.cancel(paid.getId()))
            .isInstanceOf(com.zgate.controlcenter.exception.ControlCenterException.class);
    }

    @Test
    @DisplayName("a PAID invoice overlapping the new period blocks re-billing — coverage was bought")
    void paidInvoiceBlocksRebilling() {
        LocalDateTime validUntil = LocalDateTime.now().plusDays(20);
        org.setSubscriptionValidUntil(validUntil);
        org.setSubscriptionMonthlyFee(new BigDecimal("500.00"));
        // A PAID invoice covering the upcoming period — e.g. an operator pulled validUntil back
        // after the customer already paid. Re-raising would double-bill bought coverage.
        Invoice paidCovering = Invoice.builder()
            .id(UUID.randomUUID()).organizationId(orgId).invoiceNumber("ZGS-paid")
            .type(Invoice.Type.SUBSCRIPTION)
            .periodStart(validUntil.toLocalDate().minusMonths(1))
            .periodEnd(validUntil.toLocalDate().plusMonths(11))
            .subtotal(BigDecimal.ONE).taxRate(BigDecimal.ZERO).taxAmount(BigDecimal.ZERO)
            .totalAmount(BigDecimal.ONE).currency("USD").status(Invoice.Status.PAID)
            .build();
        when(invoiceRepo.findByOrganizationIdAndTypeAndStatusIn(any(), any(), any()))
            .thenReturn(List.of(paidCovering));

        renewal.raiseDueRenewalInvoices();

        verify(invoiceRepo, never()).save(any());
    }

    @Test
    @DisplayName("an open invoice for a strictly LATER period does not block the renewal actually due")
    void laterPeriodInvoiceDoesNotBlock() {
        LocalDateTime validUntil = LocalDateTime.now().plusDays(20);
        org.setSubscriptionValidUntil(validUntil);
        org.setSubscriptionMonthlyFee(new BigDecimal("500.00"));
        // Open invoice whose period starts AFTER the new period ends — no overlap, must not block.
        Invoice later = Invoice.builder()
            .id(UUID.randomUUID()).organizationId(orgId).invoiceNumber("ZGS-later")
            .type(Invoice.Type.SUBSCRIPTION)
            .periodStart(validUntil.toLocalDate().plusMonths(13))
            .periodEnd(validUntil.toLocalDate().plusMonths(25))
            .subtotal(BigDecimal.ONE).taxRate(BigDecimal.ZERO).taxAmount(BigDecimal.ZERO)
            .totalAmount(BigDecimal.ONE).currency("USD").status(Invoice.Status.SENT)
            .build();
        when(invoiceRepo.findByOrganizationIdAndTypeAndStatusIn(any(), any(), any()))
            .thenReturn(List.of(later));

        renewal.raiseDueRenewalInvoices();

        verify(invoiceRepo).save(any());
    }

    @Test
    @DisplayName("renewal: an org inside the lead window gets a SUBSCRIPTION invoice starting where coverage ends")
    void renewalInvoiceRaisedInsideLeadWindow() {
        LocalDateTime validUntil = LocalDateTime.now().plusDays(20);
        org.setSubscriptionValidUntil(validUntil);
        org.setSubscriptionMonthlyFee(new BigDecimal("500.00"));

        renewal.raiseDueRenewalInvoices();

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepo).save(captor.capture());
        Invoice raised = captor.getValue();
        assertThat(raised.getType()).isEqualTo(Invoice.Type.SUBSCRIPTION);
        assertThat(raised.getPeriodStart()).isEqualTo(validUntil.toLocalDate());
        assertThat(raised.getPeriodEnd()).isEqualTo(validUntil.toLocalDate().plusMonths(12).minusDays(1));
        assertThat(raised.getSubtotal()).isEqualByComparingTo("6000.00");
        assertThat(raised.getTotalAmount()).isEqualByComparingTo("6900.00");

        ArgumentCaptor<InvoiceLineItem> line = ArgumentCaptor.forClass(InvoiceLineItem.class);
        verify(lineItemRepo).save(line.capture());
        assertThat(line.getValue().getQuantity()).isEqualTo(12L);
    }

    @Test
    @DisplayName("renewal: an open renewal invoice for the period means no second one — never double-bill")
    void renewalNeverDoubleBills() {
        LocalDateTime validUntil = LocalDateTime.now().plusDays(20);
        org.setSubscriptionValidUntil(validUntil);
        org.setSubscriptionMonthlyFee(new BigDecimal("500.00"));
        Invoice open = Invoice.builder()
            .id(UUID.randomUUID()).organizationId(orgId).invoiceNumber("ZGS-x")
            .type(Invoice.Type.SUBSCRIPTION)
            .periodStart(validUntil.toLocalDate())
            .periodEnd(validUntil.toLocalDate().plusMonths(12).minusDays(1))
            .subtotal(BigDecimal.ONE).taxRate(BigDecimal.ZERO).taxAmount(BigDecimal.ZERO)
            .totalAmount(BigDecimal.ONE).currency("USD").status(Invoice.Status.SENT)
            .build();
        when(invoiceRepo.findByOrganizationIdAndTypeAndStatusIn(any(), any(), any()))
            .thenReturn(List.of(open));

        renewal.raiseDueRenewalInvoices();

        verify(invoiceRepo, never()).save(any());
    }

    @Test
    @DisplayName("renewal: no fee on file / perpetual orgs / far-future subscriptions are all skipped")
    void renewalSkipsOutOfScopeOrgs() {
        // no fee
        org.setSubscriptionValidUntil(LocalDateTime.now().plusDays(20));
        org.setSubscriptionMonthlyFee(null);
        renewal.raiseDueRenewalInvoices();
        // perpetual
        org.setSubscriptionValidUntil(null);
        org.setSubscriptionMonthlyFee(new BigDecimal("500.00"));
        renewal.raiseDueRenewalInvoices();
        // not due yet
        org.setSubscriptionValidUntil(LocalDateTime.now().plusDays(200));
        renewal.raiseDueRenewalInvoices();

        verify(invoiceRepo, never()).save(any());
    }
}
