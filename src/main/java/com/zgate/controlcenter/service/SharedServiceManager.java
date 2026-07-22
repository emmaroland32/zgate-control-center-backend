package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.*;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SharedServiceManager {

    private final SharedServiceRepository serviceRepo;
    private final OrgServiceSubscriptionRepository subscriptionRepo;
    private final ServiceUsageRepository usageRepo;
    private final OrganizationRepository orgRepo;

    @Cacheable("shared-services")
    public List<SharedService> findAll() {
        return serviceRepo.findAll();
    }

    public SharedService findById(UUID id) {
        return serviceRepo.findById(id)
            .orElseThrow(() -> new ControlCenterException("Shared service not found: " + id));
    }

    public SharedService findByCode(String code) {
        return serviceRepo.findByCode(code)
            .orElseThrow(() -> new ControlCenterException("Shared service not found: " + code));
    }

    @CacheEvict(value = "shared-services", allEntries = true)
    public SharedService create(SharedService service) {
        return serviceRepo.save(service);
    }

    @CacheEvict(value = "shared-services", allEntries = true)
    public SharedService update(UUID id, SharedService updated) {
        SharedService existing = findById(id);
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setPricePerCall(updated.getPricePerCall());
        existing.setPricingModel(updated.getPricingModel());
        existing.setVolumeTiers(updated.getVolumeTiers());
        existing.setEnabled(updated.isEnabled());
        return serviceRepo.save(existing);
    }

    // --- Subscriptions ---

    public List<OrgServiceSubscription> getAllSubscriptions() {
        return subscriptionRepo.findAll();
    }

    @Cacheable("org-subscriptions")
    public List<OrgServiceSubscription> getOrgSubscriptions(UUID orgId) {
        return subscriptionRepo.findByOrganizationId(orgId);
    }

    @CacheEvict(value = "org-subscriptions", allEntries = true)
    @Transactional
    public OrgServiceSubscription enableForOrg(UUID orgId, UUID serviceId, Long callLimit, String enabledBy) {
        orgRepo.findById(orgId).orElseThrow(() -> new ControlCenterException("Organization not found: " + orgId));
        findById(serviceId);

        return subscriptionRepo.findByOrganizationIdAndServiceId(orgId, serviceId)
            .map(sub -> {
                sub.setEnabled(true);
                sub.setCallLimit(callLimit);
                sub.setEnabledBy(enabledBy);
                return subscriptionRepo.save(sub);
            })
            .orElseGet(() -> subscriptionRepo.save(OrgServiceSubscription.builder()
                .organizationId(orgId)
                .serviceId(serviceId)
                .enabled(true)
                .callLimit(callLimit)
                .enabledBy(enabledBy)
                .build()));
    }

    @CacheEvict(value = "org-subscriptions", allEntries = true)
    public void disableForOrg(UUID orgId, UUID serviceId) {
        subscriptionRepo.findByOrganizationIdAndServiceId(orgId, serviceId).ifPresent(sub -> {
            sub.setEnabled(false);
            subscriptionRepo.save(sub);
        });
    }

    // --- Usage Tracking (called by ZGATE instances via API) ---

    @Transactional
    public void trackUsage(UUID orgId, String serviceCode, long callCount, long successCount) {
        SharedService service = findByCode(serviceCode);

        // Verify org is subscribed
        subscriptionRepo.findByOrganizationIdAndServiceId(orgId, service.getId())
            .filter(OrgServiceSubscription::isEnabled)
            .orElseThrow(() -> new ControlCenterException("Service not enabled for organization"));

        LocalDate periodStart = LocalDate.now().withDayOfMonth(1);
        LocalDate periodEnd = periodStart.plusMonths(1).minusDays(1);
        BigDecimal cost = service.getPricePerCall().multiply(BigDecimal.valueOf(callCount));

        usageRepo.findByOrganizationIdAndServiceIdAndPeriodStart(orgId, service.getId(), periodStart)
            .ifPresentOrElse(usage -> {
                usage.setCallCount(usage.getCallCount() + callCount);
                usage.setSuccessCount(usage.getSuccessCount() + successCount);
                usage.setFailureCount(usage.getFailureCount() + (callCount - successCount));
                usage.setCostUsd(usage.getCostUsd().add(cost));
                usageRepo.save(usage);
            }, () -> usageRepo.save(ServiceUsage.builder()
                .organizationId(orgId)
                .serviceId(service.getId())
                .callCount(callCount)
                .successCount(successCount)
                .failureCount(callCount - successCount)
                .costUsd(cost)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .build()));

        // Update org lastSeen
        orgRepo.findById(orgId).ifPresent(org -> {
            org.setLastSeenAt(java.time.LocalDateTime.now());
            orgRepo.save(org);
        });
    }

    public List<ServiceUsage> getUsage(UUID orgId, LocalDate from, LocalDate to) {
        return usageRepo.findByOrganizationIdAndPeriodStartBetween(orgId, from, to);
    }

    public BigDecimal getCurrentMonthCost(UUID orgId) {
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = LocalDate.now();
        return usageRepo.sumCostByOrgAndPeriod(orgId, start, end);
    }
}
