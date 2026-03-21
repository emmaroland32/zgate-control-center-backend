package com.zgate.nexus.repository;

import com.zgate.nexus.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    List<Invoice> findByOrganizationId(UUID orgId);
    List<Invoice> findByStatus(Invoice.Status status);
    List<Invoice> findByOrganizationIdOrderByCreatedAtDesc(UUID orgId);
    List<Invoice> findByOrganizationIdAndPeriodStartBetween(UUID orgId, LocalDate from, LocalDate to);
    boolean existsByInvoiceNumber(String invoiceNumber);
}
