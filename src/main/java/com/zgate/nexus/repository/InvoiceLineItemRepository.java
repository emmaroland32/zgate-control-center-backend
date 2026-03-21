package com.zgate.nexus.repository;

import com.zgate.nexus.domain.InvoiceLineItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvoiceLineItemRepository extends JpaRepository<InvoiceLineItem, UUID> {
    List<InvoiceLineItem> findByInvoiceId(UUID invoiceId);
}
