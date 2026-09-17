package com.zgate.controlcenter.service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Where issued step-up tickets live between issue and their single use. Kept behind an interface
 * so the ticket logic is testable without a database; the production store is JDBC-backed.
 */
public interface StepUpTicketStore {

    /** What was recorded at issue time: the owner and, when bound, the one action it opens. */
    record Issued(String email, String action) {}

    void save(String nonce, String email, String action, LocalDateTime issuedAt, LocalDateTime expiresAt);

    /** The live (unexpired, unclaimed) ticket for this nonce, if any. */
    Optional<Issued> find(String nonce, LocalDateTime now);

    /** Consume the ticket. True for exactly one caller, even under concurrent use. */
    boolean claim(String nonce);

    int purgeExpired(LocalDateTime now);
}
