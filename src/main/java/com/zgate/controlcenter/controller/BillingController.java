package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.Invoice;
import com.zgate.controlcenter.domain.InvoiceLineItem;
import com.zgate.controlcenter.repository.InvoiceLineItemRepository;
import com.zgate.controlcenter.repository.InvoiceRepository;
import com.zgate.controlcenter.service.BillingService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final InvoiceRepository invoiceRepo;
    private final InvoiceLineItemRepository lineItemRepo;

    @GetMapping("/dashboard/{orgId}")
    public ResponseEntity<?> dashboard(@PathVariable UUID orgId) {
        return ResponseEntity.ok(billingService.getDashboard(orgId));
    }

    @GetMapping("/invoices")
    public ResponseEntity<List<Invoice>> findAll() {
        return ResponseEntity.ok(invoiceRepo.findAll());
    }

    @GetMapping("/invoices/{orgId}")
    public ResponseEntity<List<Invoice>> findByOrg(@PathVariable UUID orgId) {
        return ResponseEntity.ok(invoiceRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId));
    }

    /** Line items for an invoice (the detail modal reads these; previously there was no read endpoint). */
    @GetMapping("/invoices/{invoiceId}/line-items")
    public ResponseEntity<List<InvoiceLineItem>> lineItems(@PathVariable UUID invoiceId) {
        return ResponseEntity.ok(lineItemRepo.findByInvoiceId(invoiceId));
    }

    @PostMapping("/invoices/generate")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Invoice> generate(@RequestBody GenerateInvoiceRequest req) {
        return ResponseEntity.ok(billingService.generateInvoice(
            req.getOrganizationId(), req.getPeriodStart(), req.getPeriodEnd(), "SYSTEM"));
    }

    @PostMapping("/invoices/{id}/send")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Void> send(@PathVariable UUID id) {
        billingService.sendInvoice(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/invoices/{id}/pay")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    public ResponseEntity<Invoice> markPaid(@PathVariable UUID id) {
        return ResponseEntity.ok(billingService.markPaid(id));
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID id) {
        Invoice invoice = invoiceRepo.findById(id)
            .orElseThrow(() -> new com.zgate.controlcenter.exception.ControlCenterException("Invoice not found: " + id));
        String csv = billingService.invoiceToCsv(invoice);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=invoice-" + invoice.getInvoiceNumber() + ".pdf")
            .contentType(MediaType.APPLICATION_PDF)
            .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/invoices/export/csv")
    public ResponseEntity<byte[]> exportCsv() {
        String csv = billingService.exportInvoicesCsv();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=invoices.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Data static class GenerateInvoiceRequest {
        private UUID organizationId;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate periodStart;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate periodEnd;
    }
}
