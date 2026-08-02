package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.Invoice;
import com.zgate.controlcenter.domain.InvoiceLineItem;
import com.zgate.controlcenter.repository.InvoiceLineItemRepository;
import com.zgate.controlcenter.repository.InvoiceRepository;
import com.zgate.controlcenter.service.BillingService;
import com.zgate.controlcenter.web.ResponseMessage;
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
    public ResponseEntity<List<BillingService.InvoiceView>> findAll() {
        // Returns the org NAME and the line items alongside each invoice: the console printed a raw
        // UUID for the customer and rendered a permanently empty line-item table without them.
        return ResponseEntity.ok(billingService.listInvoices());
    }

    /** Real per-org billing accounts (replaces the previously-fabricated Accounts tab). */
    @GetMapping("/accounts")
    public ResponseEntity<List<BillingService.BillingAccount>> accounts() {
        return ResponseEntity.ok(billingService.getAllAccounts());
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
    @ResponseMessage(code = "INVOICE_GENERATED", value = "Invoice generated")
    public ResponseEntity<Invoice> generate(@RequestBody GenerateInvoiceRequest req) {
        return ResponseEntity.ok(billingService.generateInvoice(
            req.getOrganizationId(), req.getPeriodStart(), req.getPeriodEnd(), "SYSTEM"));
    }

    @PostMapping("/invoices/{id}/send")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "INVOICE_SENT", value = "Invoice sent")
    public ResponseEntity<?> send(@PathVariable UUID id) {
        billingService.sendInvoice(id);
        return ResponseEntity.ok(java.util.Map.of("id", id.toString()));
    }

    @PostMapping("/invoices/{id}/pay")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "INVOICE_PAID", value = "Invoice marked as paid")
    public ResponseEntity<Invoice> markPaid(@PathVariable UUID id) {
        return ResponseEntity.ok(billingService.markPaid(id));
    }

    /** Cancel an unpaid invoice — the escape hatch for a mis-raised renewal. Paid ones are refused. */
    @PostMapping("/invoices/{id}/cancel")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "INVOICE_CANCELLED", value = "Invoice cancelled")
    public ResponseEntity<Invoice> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(billingService.cancel(id));
    }

    /** CSV, and honestly named. This used to serve CSV bytes as application/pdf with a .pdf
     *  filename, producing a file that would not open. */
    @GetMapping("/invoices/{id}/csv")
    public ResponseEntity<byte[]> downloadInvoiceCsv(@PathVariable UUID id) {
        Invoice invoice = invoiceRepo.findById(id)
            .orElseThrow(() -> new com.zgate.controlcenter.exception.ControlCenterException("Invoice not found: " + id));
        String csv = billingService.invoiceToCsv(invoice);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=invoice-" + invoice.getInvoiceNumber() + ".csv")
            .contentType(MediaType.parseMediaType("text/csv"))
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
