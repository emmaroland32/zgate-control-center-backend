package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Deployment;
import com.zgate.controlcenter.domain.Invoice;
import com.zgate.controlcenter.domain.License;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.repository.DeploymentRepository;
import com.zgate.controlcenter.repository.InvoiceRepository;
import com.zgate.controlcenter.repository.LicenseRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.PartnerRepository;
import com.zgate.controlcenter.repository.ServiceUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    // ------------------------------------------------------------------ DTOs

    public record UsageSummaryReport(
        long totalOrganizations,
        long activeOrganizations,
        long productionOrgs,
        long stagingOrgs,
        long totalLicenses,
        long activeLicenses,
        long expiredLicenses,
        long expiringIn30Days,
        long totalDeployments,
        long successfulDeployments,
        long failedDeployments,
        long rolledBack,
        long totalInvoices,
        long paidInvoices,
        long overdueInvoices,
        long draftInvoices,
        long totalPartners
    ) {}

    public record ModuleUsageStat(
        String moduleName,
        long licensedCount,
        long activeCount
    ) {}

    public record DeploymentStat(
        String status,
        long count
    ) {}

    // --------------------------------------------------------- Repositories

    private final OrganizationRepository organizationRepo;
    private final DeploymentRepository   deploymentRepo;
    private final LicenseRepository      licenseRepo;
    private final ServiceUsageRepository serviceUsageRepo;
    private final InvoiceRepository      invoiceRepo;
    private final PartnerRepository      partnerRepo;

    // ------------------------------------------------------------ Service methods

    /**
     * Aggregates platform-wide counts across all entities into a single summary report.
     *
     * Active organizations are those whose deployment status is HEALTHY, PROVISIONING, or SUSPENDED
     * (i.e. everything except DEGRADED and OFFLINE, which signal non-operational states).
     */
    public UsageSummaryReport getUsageSummary() {
        // Organizations
        long totalOrgs      = organizationRepo.count();
        long activeOrgs     = organizationRepo.countByDeploymentStatus(Organization.DeploymentStatus.HEALTHY)
                            + organizationRepo.countByDeploymentStatus(Organization.DeploymentStatus.PROVISIONING)
                            + organizationRepo.countByDeploymentStatus(Organization.DeploymentStatus.SUSPENDED);
        long productionOrgs = organizationRepo.countByDeploymentEnv(Organization.DeploymentEnv.PRODUCTION);
        long stagingOrgs    = organizationRepo.countByDeploymentEnv(Organization.DeploymentEnv.STAGING);

        // Licenses
        long totalLicenses    = licenseRepo.count();
        long activeLicenses   = licenseRepo.countByStatus(License.Status.ACTIVE);
        long expiredLicenses  = licenseRepo.countByStatus(License.Status.EXPIRED);
        long expiringIn30Days = licenseRepo.findExpiringSoon(java.time.LocalDateTime.now().plusDays(30)).size();

        // Deployments
        long totalDeployments      = deploymentRepo.count();
        long successfulDeployments = deploymentRepo.countByStatus(Deployment.Status.SUCCESS);
        long failedDeployments     = deploymentRepo.countByStatus(Deployment.Status.FAILED);
        long rolledBack            = deploymentRepo.countByStatus(Deployment.Status.ROLLED_BACK);

        // Invoices — InvoiceRepository exposes findByStatus; derive counts by streaming
        List<Invoice> allInvoices = invoiceRepo.findAll();
        long totalInvoices   = allInvoices.size();
        long paidInvoices    = allInvoices.stream().filter(i -> i.getStatus() == Invoice.Status.PAID).count();
        long overdueInvoices = allInvoices.stream().filter(i -> i.getStatus() == Invoice.Status.OVERDUE).count();
        long draftInvoices   = allInvoices.stream().filter(i -> i.getStatus() == Invoice.Status.DRAFT).count();

        // Partners
        long totalPartners = partnerRepo.count();

        return new UsageSummaryReport(
            totalOrgs,
            activeOrgs,
            productionOrgs,
            stagingOrgs,
            totalLicenses,
            activeLicenses,
            expiredLicenses,
            expiringIn30Days,
            totalDeployments,
            successfulDeployments,
            failedDeployments,
            rolledBack,
            totalInvoices,
            paidInvoices,
            overdueInvoices,
            draftInvoices,
            totalPartners
        );
    }

    /**
     * Returns per-module license stats, grouped by the {@code moduleName} field on each license.
     * {@code licensedCount} is the total number of license records for that module;
     * {@code activeCount} is how many of those are currently in ACTIVE status.
     */
    public List<ModuleUsageStat> getModuleStats() {
        Map<String, List<License>> byModule = licenseRepo.findAll().stream()
            .collect(Collectors.groupingBy(License::getModuleName));

        return byModule.entrySet().stream()
            .map(entry -> {
                String module      = entry.getKey();
                List<License> list = entry.getValue();
                long licensed = list.size();
                long active   = list.stream().filter(l -> l.getStatus() == License.Status.ACTIVE).count();
                return new ModuleUsageStat(module, licensed, active);
            })
            .sorted(Comparator.comparing(ModuleUsageStat::moduleName))
            .collect(Collectors.toList());
    }

    /**
     * Returns a breakdown of deployment records by status, using the enum name as the label.
     * Only statuses that have at least one deployment are included in the result.
     */
    public List<DeploymentStat> getDeploymentStats() {
        Map<String, Long> countsByStatus = deploymentRepo.findAll().stream()
            .collect(Collectors.groupingBy(
                d -> d.getStatus().name(),
                Collectors.counting()
            ));

        return countsByStatus.entrySet().stream()
            .map(entry -> new DeploymentStat(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(DeploymentStat::status))
            .collect(Collectors.toList());
    }

    /**
     * Exports report data as CSV text.
     */
    public String exportCsv(String type) {
        StringBuilder sb = new StringBuilder();
        switch (type) {
            case "modules" -> {
                sb.append("Module,Licensed,Active\n");
                for (ModuleUsageStat stat : getModuleStats()) {
                    sb.append(stat.moduleName()).append(',')
                      .append(stat.licensedCount()).append(',')
                      .append(stat.activeCount()).append('\n');
                }
            }
            case "deployments" -> {
                sb.append("Status,Count\n");
                for (DeploymentStat stat : getDeploymentStats()) {
                    sb.append(stat.status()).append(',')
                      .append(stat.count()).append('\n');
                }
            }
            default -> {
                UsageSummaryReport r = getUsageSummary();
                sb.append("Metric,Value\n");
                sb.append("Total Organizations,").append(r.totalOrganizations()).append('\n');
                sb.append("Active Organizations,").append(r.activeOrganizations()).append('\n');
                sb.append("Production Orgs,").append(r.productionOrgs()).append('\n');
                sb.append("Staging Orgs,").append(r.stagingOrgs()).append('\n');
                sb.append("Total Licenses,").append(r.totalLicenses()).append('\n');
                sb.append("Active Licenses,").append(r.activeLicenses()).append('\n');
                sb.append("Expired Licenses,").append(r.expiredLicenses()).append('\n');
                sb.append("Expiring in 30 Days,").append(r.expiringIn30Days()).append('\n');
                sb.append("Total Deployments,").append(r.totalDeployments()).append('\n');
                sb.append("Successful Deployments,").append(r.successfulDeployments()).append('\n');
                sb.append("Failed Deployments,").append(r.failedDeployments()).append('\n');
                sb.append("Rolled Back,").append(r.rolledBack()).append('\n');
                sb.append("Total Invoices,").append(r.totalInvoices()).append('\n');
                sb.append("Paid Invoices,").append(r.paidInvoices()).append('\n');
                sb.append("Overdue Invoices,").append(r.overdueInvoices()).append('\n');
                sb.append("Draft Invoices,").append(r.draftInvoices()).append('\n');
                sb.append("Total Partners,").append(r.totalPartners()).append('\n');
            }
        }
        return sb.toString();
    }
}
