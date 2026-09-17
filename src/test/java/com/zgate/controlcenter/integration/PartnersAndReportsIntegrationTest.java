package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.repository.DeploymentRepository;
import com.zgate.controlcenter.repository.LicenseRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.PartnerRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partners (a thin repository-backed CRUD) and the reporting surface (summary counters, per-module
 * and per-status breakdowns, raw CSV exports) against the seeded fleet.
 */
class PartnersAndReportsIntegrationTest extends AbstractIntegrationTest {

    private static final String PARTNERS = "/api/v1/partners";
    private static final String REPORTS = "/api/v1/reports";

    @Autowired PartnerRepository partnerRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired LicenseRepository licenseRepo;
    @Autowired DeploymentRepository deploymentRepo;

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> partnerBody(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("companyName", name);
        m.put("tier", "GOLD");
        m.put("status", "ACTIVE");
        m.put("contactName", "Pat Partner");
        m.put("contactEmail", "pat@" + name.toLowerCase().replace(' ', '-') + ".example.test");
        m.put("contactPhone", "+234000000");
        m.put("country", "NG");
        m.put("region", "af-west");
        m.put("website", "https://" + name.toLowerCase().replace(' ', '-') + ".example.test");
        m.put("revenueSharePercent", "12.5");
        m.put("contractExpiry", "2030-06-30");
        return m;
    }

    private JsonNode createPartner(String token, String name) throws Exception {
        MvcResult r = post(PARTNERS, token, partnerBody(name));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        assertThat(code(r)).isEqualTo("PARTNER_CREATED");
        return data(r);
    }

    private static JsonNode byId(JsonNode list, String id) {
        for (JsonNode p : list) if (id.equals(p.path("id").asText())) return p;
        return null;
    }

    private MvcResult csv(String path, String token) throws Exception {
        MvcResult r = get(path, token);
        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).startsWith("text/csv");
        assertThat(r.getResponse().getHeader("X-Api-Envelope")).as("raw bytes bypass the envelope").isNull();
        assertThat(text(r)).doesNotStartWith("{");
        return r;
    }

    // ── partners ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("partner create is 201 with every field persisted; get and list return it")
    void createGetList() throws Exception {
        String token = adminToken();
        String name = "Partner " + uniqueSlug("it");
        JsonNode p = createPartner(token, name);
        String id = p.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(p.path("companyName").asText()).isEqualTo(name);
        assertThat(p.path("tier").asText()).isEqualTo("GOLD");
        assertThat(p.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(p.path("contactName").asText()).isEqualTo("Pat Partner");
        assertThat(p.path("website").asText()).startsWith("https://partner-it-");
        assertThat(p.path("revenueSharePercent").decimalValue()).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(p.path("contractExpiry").asText()).isEqualTo("2030-06-30");
        assertThat(p.path("createdAt").isTextual()).isTrue();
        assertThat(p.path("updatedAt").isTextual()).isTrue();

        MvcResult one = get(PARTNERS + "/" + id, token);
        assertThat(status(one)).isEqualTo(200);
        assertThat(data(one).path("companyName").asText()).isEqualTo(name);
        assertThat(data(one).path("revenueSharePercent").decimalValue()).isEqualByComparingTo(new BigDecimal("12.5"));

        MvcResult list = get(PARTNERS, token);
        assertThat(status(list)).isEqualTo(200);
        assertThat(data(list).isArray()).isTrue();
        assertThat(byId(data(list), id)).isNotNull();
        assertThat(data(list).size()).isEqualTo((int) partnerRepo.count());
    }

    @Test
    @DisplayName("partner update copies every editable field except website (documented quirk) and refuses an unknown id")
    void update() throws Exception {
        String token = adminToken();
        JsonNode created = createPartner(token, "Partner " + uniqueSlug("it-upd"));
        String id = created.path("id").asText();
        String originalWebsite = created.path("website").asText();

        Map<String, Object> upd = partnerBody("Renamed " + uniqueSlug("it-upd"));
        upd.put("tier", "PLATINUM");
        upd.put("status", "SUSPENDED");
        upd.put("contactName", "New Contact");
        upd.put("contactEmail", "new@example.test");
        upd.put("contactPhone", "+27000000");
        upd.put("country", "ZA");
        upd.put("region", "af-south");
        upd.put("website", "https://changed.example.test");
        upd.put("revenueSharePercent", "20.00");
        upd.put("contractExpiry", "2032-01-01");
        MvcResult r = put(PARTNERS + "/" + id, token, upd);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("PARTNER_UPDATED");
        JsonNode p = data(r);
        assertThat(p.path("id").asText()).isEqualTo(id);
        assertThat(p.path("companyName").asText()).isEqualTo(upd.get("companyName"));
        assertThat(p.path("tier").asText()).isEqualTo("PLATINUM");
        assertThat(p.path("status").asText()).isEqualTo("SUSPENDED");
        assertThat(p.path("contactName").asText()).isEqualTo("New Contact");
        assertThat(p.path("contactEmail").asText()).isEqualTo("new@example.test");
        assertThat(p.path("contactPhone").asText()).isEqualTo("+27000000");
        assertThat(p.path("country").asText()).isEqualTo("ZA");
        assertThat(p.path("region").asText()).isEqualTo("af-south");
        assertThat(p.path("revenueSharePercent").decimalValue()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(p.path("contractExpiry").asText()).isEqualTo("2032-01-01");
        // PartnerController.update never calls setWebsite — the stored value survives the PUT.
        assertThat(p.path("website").asText()).as("website is not copied by PUT").isEqualTo(originalWebsite);
        assertThat(data(get(PARTNERS + "/" + id, token)).path("website").asText()).isEqualTo(originalWebsite);

        UUID ghost = UUID.randomUUID();
        MvcResult missing = put(PARTNERS + "/" + ghost, token, upd);
        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing).path("message").asText()).isEqualTo("Partner not found");
        MvcResult missingGet = get(PARTNERS + "/" + ghost, token);
        assertThat(status(missingGet)).isEqualTo(400);
        assertThat(body(missingGet).path("message").asText()).isEqualTo("Partner not found");
        assertThat(status(get(PARTNERS + "/not-a-uuid", token))).isEqualTo(400);
    }

    @Test
    @DisplayName("the four seeded partners are present under their fixed ids with the seeded company and tier")
    void seedPartners() throws Exception {
        String token = adminToken();
        JsonNode list = data(get(PARTNERS, token));
        assertThat(list.size()).isGreaterThanOrEqualTo(4);
        String[][] seed = {
            {"a0000001-0000-0000-0000-000000000001", "Stellartech Solutions", "PLATINUM"},
            {"a0000002-0000-0000-0000-000000000002", "FinAxis Europe GmbH", "GOLD"},
            {"a0000003-0000-0000-0000-000000000003", "Meridian Tech Nigeria", "GOLD"},
            {"a0000004-0000-0000-0000-000000000004", "AfriaTech Partners Ltd", "SILVER"},
        };
        for (String[] s : seed) {
            JsonNode inList = byId(list, s[0]);
            assertThat(inList).as(s[1]).isNotNull();
            JsonNode p = data(get(PARTNERS + "/" + s[0], token));
            assertThat(p.path("companyName").asText()).isEqualTo(s[1]);
            assertThat(p.path("tier").asText()).isEqualTo(s[2]);
            assertThat(p.path("status").asText()).isEqualTo("ACTIVE");
        }
    }

    @Test
    @DisplayName("a VIEWER reads partners but cannot create or update; a bad tier enum is a 400")
    void partnerGatingAndEnums() throws Exception {
        String root = adminToken();
        String id = createPartner(root, "Partner " + uniqueSlug("it-gate")).path("id").asText();
        String viewer = tokenFor(root, "VIEWER");
        assertThat(status(get(PARTNERS, viewer))).isEqualTo(200);
        assertThat(status(get(PARTNERS + "/" + id, viewer))).isEqualTo(200);
        MvcResult create = post(PARTNERS, viewer, partnerBody("Partner " + uniqueSlug("it-denied")));
        assertThat(status(create)).isEqualTo(403);
        assertThat(code(create)).isEqualTo("ACCESS_DENIED");
        assertThat(status(put(PARTNERS + "/" + id, viewer, partnerBody("x")))).isEqualTo(403);

        Map<String, Object> badTier = partnerBody("Partner " + uniqueSlug("it-badtier"));
        badTier.put("tier", "DIAMOND");
        MvcResult bt = post(PARTNERS, root, badTier);
        assertThat(status(bt)).isEqualTo(400);
        assertThat(code(bt)).isEqualTo("INVALID_ENUM_VALUE");
        assertThat(body(bt).path("message").asText()).contains("PLATINUM, GOLD, SILVER, BRONZE, RESELLER");

        assertThat(status(get(PARTNERS, null))).isEqualTo(401);
    }

    @Test
    @DisplayName("a partner without a tier or status is a 400 validation failure, not a 500")
    void partnerMissingRequiredIs400() throws Exception {
        String token = adminToken();
        MvcResult r = post(PARTNERS, token, Map.of("companyName", "Partner " + uniqueSlug("it-noreq")));
        assertThat(status(r)).isEqualTo(400);
    }

    // ── reports ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the summary report carries all 17 counters and agrees with the database and the seed")
    void summary() throws Exception {
        String token = adminToken();
        MvcResult r = get(REPORTS + "/summary", token);
        assertThat(status(r)).isEqualTo(200);
        JsonNode s = data(r);
        String[] fields = {"totalOrganizations", "activeOrganizations", "productionOrgs", "stagingOrgs",
            "totalLicenses", "activeLicenses", "expiredLicenses", "expiringIn30Days",
            "totalDeployments", "successfulDeployments", "failedDeployments", "rolledBack",
            "totalInvoices", "paidInvoices", "overdueInvoices", "draftInvoices", "totalPartners"};
        assertThat(s.size()).isEqualTo(17);
        for (String f : fields) assertThat(s.path(f).isNumber()).as(f).isTrue();

        assertThat(s.path("totalOrganizations").asLong()).isEqualTo(orgRepo.count());
        assertThat(s.path("totalOrganizations").asLong()).isGreaterThanOrEqualTo(6);
        assertThat(s.path("productionOrgs").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(s.path("stagingOrgs").asLong()).as("Ninety One").isGreaterThanOrEqualTo(1);
        assertThat(s.path("activeOrganizations").asLong()).isLessThanOrEqualTo(s.path("totalOrganizations").asLong());
        assertThat(s.path("totalLicenses").asLong()).isEqualTo(licenseRepo.count());
        assertThat(s.path("totalLicenses").asLong()).isGreaterThanOrEqualTo(14);
        assertThat(s.path("activeLicenses").asLong()).isLessThanOrEqualTo(s.path("totalLicenses").asLong());
        assertThat(s.path("expiredLicenses").asLong()).as("FNB RISK_ENGINE").isGreaterThanOrEqualTo(1);
        assertThat(s.path("expiringIn30Days").asLong()).as("Apex 3 + Coronation 2").isGreaterThanOrEqualTo(5);
        assertThat(s.path("totalDeployments").asLong()).isEqualTo(deploymentRepo.count());
        assertThat(s.path("totalDeployments").asLong()).isGreaterThanOrEqualTo(6);
        assertThat(s.path("successfulDeployments").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(s.path("failedDeployments").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(s.path("totalPartners").asLong()).isEqualTo(partnerRepo.count());
        assertThat(s.path("totalPartners").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(s.path("paidInvoices").asLong() + s.path("overdueInvoices").asLong() + s.path("draftInvoices").asLong())
            .isLessThanOrEqualTo(s.path("totalInvoices").asLong());

        // Creating a partner moves the counter — the report is live, not cached.
        createPartner(token, "Partner " + uniqueSlug("it-report"));
        assertThat(data(get(REPORTS + "/summary", token)).path("totalPartners").asLong())
            .isEqualTo(s.path("totalPartners").asLong() + 1);
        assertThat(status(get(REPORTS + "/summary", tokenFor(token, "VIEWER")))).isEqualTo(200);
    }

    @Test
    @DisplayName("module stats are sorted by module and count active within licensed; deployment stats sum to the total")
    void modulesAndDeployments() throws Exception {
        String token = adminToken();
        MvcResult m = get(REPORTS + "/modules", token);
        assertThat(status(m)).isEqualTo(200);
        JsonNode modules = data(m);
        assertThat(modules.isArray()).isTrue();
        String prev = "";
        long licensedSum = 0;
        JsonNode mutualFund = null, riskEngine = null;
        for (JsonNode s : modules) {
            String name = s.path("moduleName").asText();
            assertThat(name.compareTo(prev)).as("sorted by moduleName").isGreaterThan(0);
            prev = name;
            assertThat(s.path("activeCount").asLong()).isBetween(0L, s.path("licensedCount").asLong());
            licensedSum += s.path("licensedCount").asLong();
            if ("MUTUAL_FUND".equals(name)) mutualFund = s;
            if ("RISK_ENGINE".equals(name)) riskEngine = s;
        }
        assertThat(mutualFund).isNotNull();
        assertThat(mutualFund.path("licensedCount").asLong()).as("five seeded orgs hold MUTUAL_FUND").isGreaterThanOrEqualTo(5);
        assertThat(riskEngine).isNotNull();
        assertThat(riskEngine.path("activeCount").asLong()).as("FNB's RISK_ENGINE is EXPIRED")
            .isLessThanOrEqualTo(riskEngine.path("licensedCount").asLong() - 1);
        assertThat(licensedSum).isEqualTo(licenseRepo.count());

        MvcResult d = get(REPORTS + "/deployments", token);
        assertThat(status(d)).isEqualTo(200);
        JsonNode deployments = data(d);
        prev = "";
        long total = 0;
        Map<String, Long> byStatus = new HashMap<>();
        for (JsonNode s : deployments) {
            String st = s.path("status").asText();
            assertThat(st.compareTo(prev)).isGreaterThan(0);
            prev = st;
            assertThat(s.path("count").asLong()).isGreaterThan(0);
            total += s.path("count").asLong();
            byStatus.put(st, s.path("count").asLong());
        }
        assertThat(byStatus.getOrDefault("SUCCESS", 0L)).isGreaterThanOrEqualTo(4);
        assertThat(byStatus.getOrDefault("FAILED", 0L)).isGreaterThanOrEqualTo(1);
        assertThat(byStatus.getOrDefault("IN_PROGRESS", 0L)).isGreaterThanOrEqualTo(1);
        assertThat(total).isEqualTo(deploymentRepo.count());
        assertThat(total).isEqualTo(data(get(REPORTS + "/summary", token)).path("totalDeployments").asLong());
    }

    @Test
    @DisplayName("CSV export is raw with the documented header per type, a matching Content-Disposition, and csv-detailed is byte-identical")
    void exportCsv() throws Exception {
        String token = adminToken();

        MvcResult summary = csv(REPORTS + "/export/csv", token);
        assertThat(summary.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=report-summary.csv");
        String[] lines = text(summary).split("\n");
        assertThat(lines[0]).isEqualTo("Metric,Value");
        assertThat(lines).hasSize(18);
        assertThat(lines[1]).startsWith("Total Organizations,");
        assertThat(lines[17]).startsWith("Total Partners,");
        JsonNode s = data(get(REPORTS + "/summary", token));
        assertThat(text(summary)).contains("Total Organizations," + s.path("totalOrganizations").asLong() + "\n")
            .contains("Total Partners," + s.path("totalPartners").asLong() + "\n")
            .contains("Expiring in 30 Days," + s.path("expiringIn30Days").asLong() + "\n");

        MvcResult modules = csv(REPORTS + "/export/csv?type=modules", token);
        assertThat(modules.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=report-modules.csv");
        String[] ml = text(modules).split("\n");
        assertThat(ml[0]).isEqualTo("Module,Licensed,Active");
        assertThat(ml.length - 1).isEqualTo(data(get(REPORTS + "/modules", token)).size());
        assertThat(text(modules)).containsPattern("\nMUTUAL_FUND,\\d+,\\d+\n");

        MvcResult deployments = csv(REPORTS + "/export/csv?type=deployments", token);
        assertThat(deployments.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=report-deployments.csv");
        String[] dl = text(deployments).split("\n");
        assertThat(dl[0]).isEqualTo("Status,Count");
        assertThat(text(deployments)).containsPattern("\nSUCCESS,\\d+\n").containsPattern("\nFAILED,\\d+\n");

        // Unknown type falls back to the summary body but keeps the requested name.
        MvcResult bogus = csv(REPORTS + "/export/csv?type=bogus", token);
        assertThat(bogus.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=report-bogus.csv");
        assertThat(text(bogus).split("\n")[0]).isEqualTo("Metric,Value");

        for (String type : new String[] {"summary", "modules", "deployments"}) {
            MvcResult detailed = csv(REPORTS + "/export/csv-detailed?type=" + type, token);
            assertThat(detailed.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=report-" + type + ".csv");
            assertThat(text(detailed).split("\n")[0]).isEqualTo(text(csv(REPORTS + "/export/csv?type=" + type, token)).split("\n")[0]);
        }
        assertThat(text(csv(REPORTS + "/export/csv-detailed?type=modules", token)))
            .isEqualTo(text(csv(REPORTS + "/export/csv?type=modules", token)));

        assertThat(status(get(REPORTS + "/export/csv", tokenFor(token, "VIEWER")))).isEqualTo(200);
        assertThat(status(get(REPORTS + "/export/csv", null))).isEqualTo(401);
    }
}
