package com.zgate.nexus.controller;

import com.zgate.nexus.domain.Partner;
import com.zgate.nexus.exception.NexusException;
import com.zgate.nexus.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/partners")
@RequiredArgsConstructor
public class PartnerController {

    private final PartnerRepository repo;

    @GetMapping
    public ResponseEntity<List<Partner>> findAll() { return ResponseEntity.ok(repo.findAll()); }

    @GetMapping("/{id}")
    public ResponseEntity<Partner> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(repo.findById(id).orElseThrow(() -> new NexusException("Partner not found")));
    }

    @PostMapping
    public ResponseEntity<Partner> create(@RequestBody Partner partner) {
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(partner));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Partner> update(@PathVariable UUID id, @RequestBody Partner updated) {
        Partner p = repo.findById(id).orElseThrow(() -> new NexusException("Partner not found"));
        p.setCompanyName(updated.getCompanyName());
        p.setTier(updated.getTier());
        p.setStatus(updated.getStatus());
        p.setContactName(updated.getContactName());
        p.setContactEmail(updated.getContactEmail());
        p.setContactPhone(updated.getContactPhone());
        p.setCountry(updated.getCountry());
        p.setRegion(updated.getRegion());
        p.setRevenueSharePercent(updated.getRevenueSharePercent());
        p.setContractExpiry(updated.getContractExpiry());
        return ResponseEntity.ok(repo.save(p));
    }
}
