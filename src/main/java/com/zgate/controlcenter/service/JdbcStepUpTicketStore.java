package com.zgate.controlcenter.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/** Step-up tickets in {@code step_up_tickets} (V34). The DELETE is what makes a ticket single-use. */
@Repository
@RequiredArgsConstructor
public class JdbcStepUpTicketStore implements StepUpTicketStore {

    private final JdbcTemplate jdbc;

    @Override
    public void save(String nonce, String email, String action, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        jdbc.update("INSERT INTO step_up_tickets (nonce, email, action, issued_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                    nonce, email, action, issuedAt, expiresAt);
    }

    @Override
    public Optional<Issued> find(String nonce, LocalDateTime now) {
        return jdbc.query("SELECT email, action FROM step_up_tickets WHERE nonce = ? AND expires_at > ?",
                          (rs, i) -> new Issued(rs.getString("email"), rs.getString("action")), nonce, now)
                   .stream().findFirst();
    }

    @Override
    public boolean claim(String nonce) {
        // Two concurrent requests presenting the same ticket both pass the checks above; only the
        // one whose DELETE removes the row is allowed through.
        return jdbc.update("DELETE FROM step_up_tickets WHERE nonce = ?", nonce) == 1;
    }

    @Override
    public int purgeExpired(LocalDateTime now) {
        return jdbc.update("DELETE FROM step_up_tickets WHERE expires_at < ?", now);
    }
}
