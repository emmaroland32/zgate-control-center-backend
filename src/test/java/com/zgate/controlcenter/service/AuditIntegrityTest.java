package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.repository.AuditLogRepository;
import com.zgate.controlcenter.service.provisioning.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tamper-evidence for the record of who destroyed a customer's production system. An edit made
 * directly in the database — with credentials that were, until today, recoverable from git history
 * — must not be able to carry a matching signature.
 */
class AuditIntegrityTest {

    private AuditLogRepository repo;
    private SecretCipher cipher;
    private AuditService svc;

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]), "", "v1");
        ReflectionTestUtils.invokeMethod(cipher, "init");
        svc = new AuditService(repo, cipher);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private AuditLog written() {
        svc.log("admin", "admin@zgate.io", "PROVISION_DESTROYED", "InfrastructureStack",
                "stack-1", UUID.randomUUID(), "203.0.113.9", "target=aws-ecs",
                AuditLog.Status.SUCCESS);
        ArgumentCaptor<AuditLog> c = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("a row is signed at write time and verifies clean")
    void signedAndVerifies() {
        AuditLog row = written();
        assertThat(row.getIntegrityHash()).isNotBlank().hasSize(64);

        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));
        var report = svc.verify(100);
        assertThat(report.valid()).isEqualTo(1);
        assertThat(report.tamperedCount()).isZero();
        assertThat(report.signingConfigured()).isTrue();
    }

    @Test
    @DisplayName("editing a row in the database is detected")
    void tamperIsDetected() {
        AuditLog row = written();
        // Someone with database access rewrites who did it — the signature no longer matches.
        row.setActor("someone-else");

        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));
        var report = svc.verify(100);
        assertThat(report.tamperedCount()).isEqualTo(1);
        assertThat(report.valid()).isZero();
        assertThat(report.tampered().get(0)).contains("PROVISION_DESTROYED");
    }

    @Test
    @DisplayName("every signed field is covered — changing any one of them breaks the signature")
    void allFieldsCovered() {
        record Mutation(String name, java.util.function.Consumer<AuditLog> apply) {}
        List<Mutation> mutations = List.of(
            new Mutation("action", r -> r.setAction("SOMETHING_ELSE")),
            new Mutation("entityId", r -> r.setEntityId("stack-999")),
            new Mutation("ipAddress", r -> r.setIpAddress("10.0.0.1")),
            new Mutation("details", r -> r.setDetails("target=nothing")),
            new Mutation("status", r -> r.setStatus(AuditLog.Status.FAILURE)),
            new Mutation("organizationId", r -> r.setOrganizationId(UUID.randomUUID())));

        for (Mutation m : mutations) {
            reset(repo);
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
            AuditLog row = written();
            m.apply().accept(row);
            when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));
            assertThat(svc.verify(100).tamperedCount())
                .describedAs("mutating %s must break the signature", m.name())
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("with no key the row is written UNSIGNED rather than lost, and reported as such")
    void unsignedWhenNoKey() {
        SecretCipher unconfigured = new SecretCipher("", "", "v1");
        ReflectionTestUtils.invokeMethod(unconfigured, "init");
        AuditService s = new AuditService(repo, unconfigured);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        s.log("admin", "a@b.c", "USER_DISABLED", "ControlCenterUser", "u1", null, "1.2.3.4", null,
              AuditLog.Status.SUCCESS);
        ArgumentCaptor<AuditLog> c = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(c.capture());
        // The audit record still exists — losing it would be worse than leaving it unsigned.
        assertThat(c.getValue().getAction()).isEqualTo("USER_DISABLED");
        assertThat(c.getValue().getIntegrityHash()).isNull();

        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(c.getValue())));
        var report = s.verify(100);
        // Unsigned is its own bucket: not counted as valid, not cried wolf over as tampered.
        assertThat(report.unsigned()).isEqualTo(1);
        assertThat(report.valid()).isZero();
        assertThat(report.tamperedCount()).isZero();
        assertThat(report.signingConfigured()).isFalse();
    }
}
