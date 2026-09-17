package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.repository.OrganizationRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared-services catalogue, per-org subscriptions, the M2M usage tracker and the invoice
 * pipeline that bills that usage — end to end over HTTP. Every organization is created fresh; the
 * seeded catalogue rows are read but never mutated.
 */
class SharedServicesAndBillingIntegrationTest extends AbstractIntegrationTest {

    private static final String SVC = "/api/v1/shared-services";
    private static final String BILLING = "/api/v1/billing";
    private static final String ORG_HEADER = "X-Control-Center-Org-Id";

    private static final List<String> SEEDED_CODES = List.of(
        "NIN_VALIDATION", "BVN_VALIDATION", "RSA_ID_VERIFICATION", "PASSPORT_VERIFICATION",
        "DRIVERS_LICENSE", "GHANA_CARD", "OFAC_SCREENING", "UN_SANCTIONS", "EU_SANCTIONS",
        "UK_HMT_SANCTIONS", "PEP_SCREENING", "ADVERSE_MEDIA", "AML_RISK_SCORE", "DOCUMENT_VERIFY",
        "FACE_LIVENESS", "ADDRESS_VERIFY", "CRC_CREDIT", "TRANSUNION_CREDIT", "EXPERIAN_CREDIT",
        "XDS_CREDIT", "SMS_OTP", "EMAIL_VERIFY", "PHONE_VALIDATE");

    @Autowired OrganizationRepository orgRepo;

    // ── helpers ─────────────────────────────────────────────────────────────

    private JsonNode createOrg(String token, boolean withContactEmail) throws Exception {
        Map<String, Object> body = withContactEmail
            ? Map.of("name", uniqueSlug("Billing Org"), "slug", uniqueSlug("billing-org"),
                     "tier", "STANDARD", "deploymentEnv", "PRODUCTION",
                     "contactEmail", unique("billing"), "country", "NG")
            : Map.of("name", uniqueSlug("Billing Org"), "slug", uniqueSlug("billing-org"),
                     "tier", "STANDARD", "deploymentEnv", "PRODUCTION");
        MvcResult r = post("/api/v1/organizations", token, body);
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        return data(r);
    }

    private JsonNode serviceByCode(String token, String code) throws Exception {
        for (JsonNode n : data(get(SVC, token))) if (code.equals(n.path("code").asText())) return n;
        throw new AssertionError("no shared service with code " + code);
    }

    private void enable(String token, String orgId, String serviceId, Long callLimit) throws Exception {
        Map<String, Object> body = callLimit == null
            ? Map.of("serviceId", serviceId, "enabledBy", "it")
            : Map.of("serviceId", serviceId, "callLimit", callLimit, "enabledBy", "it");
        MvcResult r = post(SVC + "/subscriptions/" + orgId + "/enable", token, body);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("SERVICE_ENABLED");
    }

    private MvcResult track(String orgId, String serviceCode, long calls, long ok) throws Exception {
        return postWith(SVC + "/track", null,
            Map.of("serviceCode", serviceCode, "callCount", calls, "successCount", ok),
            Map.of(ORG_HEADER, orgId));
    }

    private JsonNode generateInvoice(String token, String orgId, LocalDate from, LocalDate to) throws Exception {
        MvcResult r = post(BILLING + "/invoices/generate", token,
            Map.of("organizationId", orgId, "periodStart", from.toString(), "periodEnd", to.toString()));
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("INVOICE_GENERATED");
        return data(r);
    }

    private static LocalDate monthStart() { return LocalDate.now().withDayOfMonth(1); }
    private static LocalDate monthEnd() { return monthStart().plusMonths(1).minusDays(1); }

    private static boolean containsId(JsonNode array, String id) {
        for (JsonNode n : array) if (id.equals(n.path("id").asText())) return true;
        return false;
    }

    // ── catalogue ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the catalogue lists every seeded service and get-by-id returns it; an unknown id is a 400")
    void catalogueListAndGet() throws Exception {
        String admin = adminToken();
        MvcResult list = get(SVC, admin);
        assertThat(status(list)).isEqualTo(200);
        JsonNode all = data(list);
        assertThat(all.isArray()).isTrue();
        assertThat(all.size()).isGreaterThanOrEqualTo(22);
        assertThat(all.findValuesAsText("code")).containsAll(SEEDED_CODES);

        JsonNode nin = serviceByCode(admin, "NIN_VALIDATION");
        assertThat(nin.path("category").asText()).isEqualTo("IDENTITY");
        assertThat(nin.path("pricePerCall").decimalValue()).isPositive();
        assertThat(nin.path("currency").asText()).isEqualTo("USD");
        assertThat(nin.path("pricingModel").asText()).isEqualTo("PER_CALL");

        MvcResult one = get(SVC + "/" + nin.path("id").asText(), admin);
        assertThat(status(one)).isEqualTo(200);
        assertThat(data(one).path("code").asText()).isEqualTo("NIN_VALIDATION");

        MvcResult missing = get(SVC + "/" + UUID.randomUUID(), admin);
        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing).path("message").asText()).startsWith("Shared service not found");

        assertThat(status(get(SVC, tokenFor(admin, "VIEWER")))).isEqualTo(200);
    }

    @Test
    @DisplayName("create needs only name/code/category/price; PUT updates the commercial fields but never code or category")
    void createAndUpdateService() throws Exception {
        String admin = adminToken();
        String code = uniqueSlug("IT_SVC").toUpperCase();
        MvcResult created = post(SVC, admin, Map.of(
            "name", "IT service " + code, "code", code, "category", "IDENTITY", "pricePerCall", 0.05));
        assertThat(status(created)).as(text(created)).isEqualTo(200);
        assertThat(code(created)).isEqualTo("SERVICE_CREATED");
        JsonNode svc = data(created);
        String id = svc.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(svc.path("enabled").asBoolean()).isTrue();
        assertThat(svc.path("currency").asText()).isEqualTo("USD");
        assertThat(svc.path("pricingModel").asText()).isEqualTo("PER_CALL");
        assertThat(svc.path("pricePerCall").decimalValue()).isEqualByComparingTo("0.05");
        assertThat(containsId(data(get(SVC, admin)), id)).as("cache evicted on create").isTrue();

        MvcResult upd = put(SVC + "/" + id, admin, Map.ofEntries(
            Map.entry("name", "Renamed " + code),
            Map.entry("code", code + "-CHANGED"),
            Map.entry("category", "CREDIT"),
            Map.entry("description", "described"),
            Map.entry("provider", "acme"),
            Map.entry("pricePerCall", 0.1),
            Map.entry("pricingModel", "FLAT"),
            Map.entry("volumeTiers", "[{\"from\":0,\"to\":100,\"price\":0.1}]"),
            Map.entry("enabled", false)));
        assertThat(status(upd)).as(text(upd)).isEqualTo(200);
        assertThat(code(upd)).isEqualTo("SERVICE_UPDATED");
        JsonNode u = data(upd);
        assertThat(u.path("name").asText()).isEqualTo("Renamed " + code);
        assertThat(u.path("description").asText()).isEqualTo("described");
        assertThat(u.path("pricePerCall").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(u.path("pricingModel").asText()).isEqualTo("FLAT");
        assertThat(u.path("volumeTiers").asText()).contains("\"price\":0.1");
        assertThat(u.path("enabled").asBoolean()).isFalse();
        // Actual behaviour: SharedServiceManager.update copies name/description/price/pricingModel/
        // volumeTiers/enabled only — code, category and provider from the body are ignored.
        assertThat(u.path("code").asText()).as("code not updatable").isEqualTo(code);
        assertThat(u.path("category").asText()).as("category not updatable").isEqualTo("IDENTITY");
        assertThat(u.path("provider").isNull()).as("provider not updatable").isTrue();

        MvcResult missing = put(SVC + "/" + UUID.randomUUID(), admin, Map.of(
            "name", "x", "code", "X", "category", "IDENTITY", "pricePerCall", 1));
        assertThat(status(missing)).isEqualTo(400);

        String viewer = tokenFor(admin, "VIEWER");
        MvcResult denied = post(SVC, viewer, Map.of(
            "name", "v", "code", uniqueSlug("V"), "category", "IDENTITY", "pricePerCall", 1));
        assertThat(status(denied)).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("ACCESS_DENIED");
        assertThat(code(put(SVC + "/" + id, viewer, Map.of("name", "v")))).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("creating a service with a code that already exists is a clean 409, not a 500")
    void duplicateServiceCodeIs409() throws Exception {
        String admin = adminToken();
        MvcResult dup = post(SVC, admin, Map.of(
            "name", "dup", "code", "NIN_VALIDATION", "category", "IDENTITY", "pricePerCall", 0.05));
        assertThat(status(dup)).isEqualTo(409);
    }

    // ── subscriptions ───────────────────────────────────────────────────────

    @Test
    @DisplayName("enable creates a subscription visible in the org list, the fleet list and the quota view; disable keeps the row but flags it off")
    void subscriptionsEnableDisableQuotas() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin, true).path("id").asText();
        String serviceId = serviceByCode(admin, "NIN_VALIDATION").path("id").asText();

        MvcResult en = post(SVC + "/subscriptions/" + orgId + "/enable", admin,
            Map.of("serviceId", serviceId, "callLimit", 100, "enabledBy", "it-operator"));
        assertThat(status(en)).as(text(en)).isEqualTo(200);
        assertThat(code(en)).isEqualTo("SERVICE_ENABLED");
        JsonNode sub = data(en);
        assertThat(sub.path("organizationId").asText()).isEqualTo(orgId);
        assertThat(sub.path("serviceId").asText()).isEqualTo(serviceId);
        assertThat(sub.path("enabled").asBoolean()).isTrue();
        assertThat(sub.path("callLimit").asLong()).isEqualTo(100);
        assertThat(sub.path("enabledBy").asText()).isEqualTo("it-operator");
        assertThat(sub.has("apiKeyHash")).isTrue();

        JsonNode mine = data(get(SVC + "/subscriptions/" + orgId, admin));
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).path("id").asText()).isEqualTo(sub.path("id").asText());
        assertThat(containsId(data(get(SVC + "/subscriptions", admin)), sub.path("id").asText())).isTrue();

        JsonNode quotas = data(get(SVC + "/quotas", admin));
        JsonNode row = null;
        for (JsonNode q : quotas) if (orgId.equals(q.path("organizationId").asText())) row = q;
        assertThat(row).as("quota row for the new subscription").isNotNull();
        assertThat(row.path("serviceId").asText()).isEqualTo(serviceId);
        assertThat(row.path("serviceName").asText()).isNotBlank();
        assertThat(row.path("orgName").asText()).isNotBlank();
        assertThat(row.path("callLimit").asLong()).isEqualTo(100);
        assertThat(row.path("usedThisMonth").asLong()).isZero();
        assertThat(row.path("usedPct").asDouble()).isEqualTo(0.0);

        // Re-enabling the same pair updates the existing row (UNIQUE(org, service)), no duplicate.
        enable(admin, orgId, serviceId, 250L);
        JsonNode again = data(get(SVC + "/subscriptions/" + orgId, admin));
        assertThat(again.size()).isEqualTo(1);
        assertThat(again.get(0).path("callLimit").asLong()).isEqualTo(250);

        MvcResult dis = post(SVC + "/subscriptions/" + orgId + "/disable?serviceId=" + serviceId, admin, null);
        assertThat(status(dis)).isEqualTo(200);
        assertThat(code(dis)).isEqualTo("SERVICE_DISABLED");
        assertThat(data(dis).path("orgId").asText()).isEqualTo(orgId);
        assertThat(data(dis).path("serviceId").asText()).isEqualTo(serviceId);
        JsonNode after = data(get(SVC + "/subscriptions/" + orgId, admin));
        assertThat(after.size()).isEqualTo(1);
        assertThat(after.get(0).path("enabled").asBoolean()).as("cache evicted on disable").isFalse();
        for (JsonNode q : data(get(SVC + "/quotas", admin))) {
            assertThat(q.path("organizationId").asText()).as("disabled rows leave the quota view").isNotEqualTo(orgId);
        }

        assertThat(status(post(SVC + "/subscriptions/" + UUID.randomUUID() + "/enable", admin,
            Map.of("serviceId", serviceId, "enabledBy", "it")))).isEqualTo(400);
        assertThat(status(post(SVC + "/subscriptions/" + orgId + "/enable", admin,
            Map.of("serviceId", UUID.randomUUID().toString(), "enabledBy", "it")))).isEqualTo(400);
        assertThat(status(post(SVC + "/subscriptions/" + orgId + "/disable", admin, null)))
            .as("serviceId is a required query param").isEqualTo(400);
        assertThat(code(post(SVC + "/subscriptions/" + orgId + "/enable", tokenFor(admin, "SUPPORT"),
            Map.of("serviceId", serviceId, "enabledBy", "it")))).isEqualTo("ACCESS_DENIED");
    }

    // ── usage tracking (M2M) ────────────────────────────────────────────────

    @Test
    @DisplayName("/track is keyless with enforce=false: unknown code and unsubscribed org are 400; a subscribed org accrues a monthly usage row and a heartbeat")
    void trackUsage() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin, true).path("id").asText();
        JsonNode bvn = serviceByCode(admin, "BVN_VALIDATION");
        BigDecimal price = bvn.path("pricePerCall").decimalValue();

        MvcResult unknown = track(orgId, "NO_SUCH_SERVICE_" + uniqueSlug("x"), 1, 1);
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).path("message").asText()).startsWith("Shared service not found");

        MvcResult notSubscribed = track(orgId, "BVN_VALIDATION", 1, 1);
        assertThat(status(notSubscribed)).isEqualTo(400);
        assertThat(body(notSubscribed).path("message").asText()).isEqualTo("Service not enabled for organization");
        assertThat(data(get(SVC + "/usage/" + orgId + "?from=" + monthStart() + "&to=" + monthEnd(), admin)).size())
            .as("a refused track writes nothing").isZero();

        enable(admin, orgId, bvn.path("id").asText(), 1000L);
        assertThat(status(track(orgId, "BVN_VALIDATION", 10, 8))).isEqualTo(200);
        assertThat(status(track(orgId, "BVN_VALIDATION", 5, 5))).isEqualTo(200);

        JsonNode usage = data(get(SVC + "/usage/" + orgId + "?from=" + monthStart() + "&to=" + monthEnd(), admin));
        assertThat(usage.size()).as("one row per (org, service, month)").isEqualTo(1);
        JsonNode u = usage.get(0);
        assertThat(u.path("serviceId").asText()).isEqualTo(bvn.path("id").asText());
        assertThat(u.path("callCount").asLong()).isEqualTo(15);
        assertThat(u.path("successCount").asLong()).isEqualTo(13);
        assertThat(u.path("failureCount").asLong()).isEqualTo(2);
        assertThat(u.path("costUsd").decimalValue()).isEqualByComparingTo(price.multiply(BigDecimal.valueOf(15)));
        assertThat(u.path("periodStart").asText()).isEqualTo(monthStart().toString());
        assertThat(u.path("periodEnd").asText()).isEqualTo(monthEnd().toString());

        // Heartbeat side effect.
        Organization org = orgRepo.findById(UUID.fromString(orgId)).orElseThrow();
        assertThat(org.getLastSeenAt()).isNotNull();
        assertThat(data(get("/api/v1/organizations/" + orgId, admin)).path("lastSeenAt").isNull()).isFalse();

        // Quota view now reflects the month's calls.
        for (JsonNode q : data(get(SVC + "/quotas", admin))) {
            if (orgId.equals(q.path("organizationId").asText())) {
                assertThat(q.path("usedThisMonth").asLong()).isEqualTo(15);
                assertThat(q.path("usedPct").asDouble()).isEqualTo(1.5);
            }
        }

        // A disabled subscription refuses tracking again.
        post(SVC + "/subscriptions/" + orgId + "/disable?serviceId=" + bvn.path("id").asText(), admin, null);
        assertThat(status(track(orgId, "BVN_VALIDATION", 1, 1))).isEqualTo(400);

        // Usage report params are mandatory ISO dates.
        MvcResult noDates = get(SVC + "/usage/" + orgId, admin);
        assertThat(status(noDates)).isEqualTo(400);
        assertThat(code(noDates)).isEqualTo("MISSING_PARAMETER");
        // A window that excludes the current month returns nothing.
        assertThat(data(get(SVC + "/usage/" + orgId + "?from=2000-01-01&to=2000-01-31", admin)).size()).isZero();
    }

    @Test
    @DisplayName("/track without the org header is a clean 400, not a 500")
    void trackWithoutOrgHeaderIs400() throws Exception {
        MvcResult r = post(SVC + "/track", null, Map.of("serviceCode", "BVN_VALIDATION", "callCount", 1, "successCount", 1));
        assertThat(status(r)).isEqualTo(400);
    }

    // ── billing ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tracked usage becomes a DRAFT invoice with line items, visible in every invoice view and both CSVs; pay is idempotent and blocks cancel")
    void invoiceLifecycle() throws Exception {
        String admin = adminToken();
        JsonNode org = createOrg(admin, true);
        String orgId = org.path("id").asText();
        JsonNode svc = serviceByCode(admin, "EMAIL_VERIFY");
        BigDecimal price = svc.path("pricePerCall").decimalValue();
        enable(admin, orgId, svc.path("id").asText(), null);
        assertThat(status(track(orgId, "EMAIL_VERIFY", 100, 100))).isEqualTo(200);
        BigDecimal expectedCost = price.multiply(BigDecimal.valueOf(100));

        // currentMonthCost is deliberately NOT asserted here — see currentMonthCostReflectsThisMonthsUsage.
        JsonNode dashBefore = data(get(BILLING + "/dashboard/" + orgId, admin));
        assertThat(dashBefore.path("invoices").size()).isZero();
        assertThat(dashBefore.path("lastInvoice").isNull()).isTrue();
        assertThat(dashBefore.path("outstanding").decimalValue()).isEqualByComparingTo("0");

        JsonNode account = null;
        for (JsonNode a : data(get(BILLING + "/accounts", admin))) {
            if (orgId.equals(a.path("organizationId").asText())) account = a;
        }
        assertThat(account).isNotNull();
        assertThat(account.path("organizationName").asText()).isEqualTo(org.path("name").asText());
        assertThat(account.path("billingEmail").asText()).isEqualTo(org.path("contactEmail").asText());
        assertThat(account.path("country").asText()).isEqualTo("NG");
        assertThat(account.path("outstandingBalanceUsd").decimalValue()).isEqualByComparingTo("0");

        JsonNode inv = generateInvoice(admin, orgId, monthStart(), monthEnd());
        String invoiceId = inv.path("id").asText();
        String number = inv.path("invoiceNumber").asText();
        assertThat(number).startsWith("ZGN-");
        assertThat(inv.path("status").asText()).isEqualTo("DRAFT");
        assertThat(inv.path("type").asText()).isEqualTo("USAGE");
        assertThat(inv.path("currency").asText()).isEqualTo("USD");
        assertThat(inv.path("organizationId").asText()).isEqualTo(orgId);
        assertThat(inv.path("generatedBy").asText()).isEqualTo("SYSTEM");
        BigDecimal subtotal = inv.path("subtotal").decimalValue();
        BigDecimal taxRate = inv.path("taxRate").decimalValue();
        BigDecimal tax = inv.path("taxAmount").decimalValue();
        BigDecimal total = inv.path("totalAmount").decimalValue();
        assertThat(subtotal).isEqualByComparingTo(expectedCost.setScale(2, RoundingMode.HALF_UP));
        assertThat(taxRate).isEqualByComparingTo("0.15");
        assertThat(tax).isEqualByComparingTo(subtotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP));
        assertThat(total).isEqualByComparingTo(subtotal.add(tax));
        assertThat(inv.path("dueDate").asText()).isEqualTo(LocalDate.now().plusDays(30).toString());

        JsonNode items = data(get(BILLING + "/invoices/" + invoiceId + "/line-items", admin));
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("description").asText()).endsWith(" API calls");
        assertThat(items.get(0).path("quantity").asLong()).isEqualTo(100);
        assertThat(items.get(0).path("unitPrice").decimalValue()).isEqualByComparingTo(price);
        assertThat(items.get(0).path("totalPrice").decimalValue()).as("lines foot to the header").isEqualByComparingTo(subtotal);
        assertThat(items.get(0).path("serviceId").asText()).isEqualTo(svc.path("id").asText());

        JsonNode view = null;
        for (JsonNode v : data(get(BILLING + "/invoices", admin))) {
            if (invoiceId.equals(v.path("invoice").path("id").asText())) view = v;
        }
        assertThat(view).as("InvoiceView in the global list").isNotNull();
        assertThat(view.path("organizationName").asText()).isEqualTo(org.path("name").asText());
        assertThat(view.path("lineItems").size()).isEqualTo(1);
        assertThat(containsId(data(get(BILLING + "/invoices/" + orgId, admin)), invoiceId)).isTrue();
        JsonNode dashAfter = data(get(BILLING + "/dashboard/" + orgId, admin));
        assertThat(dashAfter.path("lastInvoice").path("id").asText()).isEqualTo(invoiceId);
        assertThat(dashAfter.path("outstanding").decimalValue()).as("DRAFT is not outstanding").isEqualByComparingTo("0");

        // CSV downloads bypass the JSON envelope entirely.
        MvcResult csv = get(BILLING + "/invoices/" + invoiceId + "/csv", admin);
        assertThat(status(csv)).isEqualTo(200);
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getHeader("X-Api-Envelope")).isNull();
        assertThat(csv.getResponse().getHeader("Content-Disposition")).contains("invoice-" + number + ".csv");
        assertThat(text(csv)).startsWith("Invoice Number,Organization,Status,Total (USD)").contains(number + "," + orgId + ",DRAFT,");
        MvcResult export = get(BILLING + "/invoices/export/csv", admin);
        assertThat(status(export)).isEqualTo(200);
        assertThat(export.getResponse().getContentType()).startsWith("text/csv");
        assertThat(text(export)).contains(number);
        MvcResult csvMissing = get(BILLING + "/invoices/" + UUID.randomUUID() + "/csv", admin);
        assertThat(status(csvMissing)).isEqualTo(400);
        assertThat(body(csvMissing).path("message").asText()).startsWith("Invoice not found");

        // Pay.
        MvcResult paid = post(BILLING + "/invoices/" + invoiceId + "/pay", admin, null);
        assertThat(status(paid)).as(text(paid)).isEqualTo(200);
        assertThat(code(paid)).isEqualTo("INVOICE_PAID");
        assertThat(data(paid).path("status").asText()).isEqualTo("PAID");
        String paidAt = data(paid).path("paidAt").asText();
        assertThat(paidAt).isNotBlank();
        // Actual behaviour: a second pay is idempotent (200, same paidAt), not a 409 —
        // BillingService.markPaid returns early on PAID so the audit timestamp is never smeared.
        MvcResult payAgain = post(BILLING + "/invoices/" + invoiceId + "/pay", admin, null);
        assertThat(status(payAgain)).isEqualTo(200);
        assertThat(data(payAgain).path("status").asText()).isEqualTo("PAID");
        assertThat(data(payAgain).path("paidAt").asText()).isEqualTo(paidAt);
        // Cancel after pay is the guarded transition.
        MvcResult cancel = post(BILLING + "/invoices/" + invoiceId + "/cancel", admin, null);
        assertThat(status(cancel)).isEqualTo(409);
        assertThat(code(cancel)).isEqualTo("INVOICE_ALREADY_PAID");
        assertThat(text(csv)).contains("DRAFT");
        assertThat(text(get(BILLING + "/invoices/" + invoiceId + "/csv", admin))).contains(",PAID,");

        // Mutations are ADMIN-only; reads are open.
        String viewer = tokenFor(admin, "VIEWER");
        assertThat(status(get(BILLING + "/invoices", viewer))).isEqualTo(200);
        assertThat(status(get(BILLING + "/accounts", viewer))).isEqualTo(200);
        assertThat(code(post(BILLING + "/invoices/generate", viewer,
            Map.of("organizationId", orgId, "periodStart", monthStart().toString(), "periodEnd", monthEnd().toString())))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(BILLING + "/invoices/" + invoiceId + "/pay", viewer, null))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(BILLING + "/invoices/" + invoiceId + "/cancel", viewer, null))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(BILLING + "/invoices/" + invoiceId + "/send", viewer, null))).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("the billing dashboard and accounts report this month's tracked usage as the current-month cost")
    void currentMonthCostReflectsThisMonthsUsage() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin, true).path("id").asText();
        JsonNode svc = serviceByCode(admin, "ADDRESS_VERIFY");
        BigDecimal expected = svc.path("pricePerCall").decimalValue().multiply(BigDecimal.valueOf(40));
        enable(admin, orgId, svc.path("id").asText(), null);
        assertThat(status(track(orgId, "ADDRESS_VERIFY", 40, 40))).isEqualTo(200);

        assertThat(data(get(BILLING + "/dashboard/" + orgId, admin)).path("currentMonthCost").decimalValue())
            .isEqualByComparingTo(expected);
        for (JsonNode a : data(get(BILLING + "/accounts", admin))) {
            if (orgId.equals(a.path("organizationId").asText())) {
                assertThat(a.path("currentMonthEstimateUsd").decimalValue()).isEqualByComparingTo(expected);
            }
        }
    }

    @Test
    @DisplayName("a DRAFT can be cancelled, after which pay is refused with INVOICE_CANCELLED")
    void cancelThenPay() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin, true).path("id").asText();
        JsonNode svc = serviceByCode(admin, "SMS_OTP");
        enable(admin, orgId, svc.path("id").asText(), null);
        assertThat(status(track(orgId, "SMS_OTP", 20, 20))).isEqualTo(200);
        String invoiceId = generateInvoice(admin, orgId, monthStart(), monthEnd()).path("id").asText();

        MvcResult cancel = post(BILLING + "/invoices/" + invoiceId + "/cancel", admin, null);
        assertThat(status(cancel)).as(text(cancel)).isEqualTo(200);
        assertThat(code(cancel)).isEqualTo("INVOICE_CANCELLED");
        assertThat(data(cancel).path("status").asText()).isEqualTo("CANCELLED");

        MvcResult pay = post(BILLING + "/invoices/" + invoiceId + "/pay", admin, null);
        assertThat(status(pay)).isEqualTo(409);
        assertThat(code(pay)).isEqualTo("INVOICE_CANCELLED");
        assertThat(data(get(BILLING + "/invoices/" + orgId, admin)).get(0).path("status").asText()).isEqualTo("CANCELLED");

        MvcResult unknownPay = post(BILLING + "/invoices/" + UUID.randomUUID() + "/pay", admin, null);
        assertThat(status(unknownPay)).isEqualTo(400);
        assertThat(body(unknownPay).path("message").asText()).isEqualTo("Invoice not found");
        assertThat(status(post(BILLING + "/invoices/" + UUID.randomUUID() + "/cancel", admin, null))).isEqualTo(400);
    }

    @Test
    @DisplayName("send fails deterministically without a mail path and leaves the invoice DRAFT")
    void sendWithoutSmtp() throws Exception {
        String admin = adminToken();
        // No contactEmail on the org: MimeMessageHelper.setTo(null) throws before any SMTP
        // connection is attempted, so this is hermetic as well as deterministic.
        String orgId = createOrg(admin, false).path("id").asText();
        JsonNode svc = serviceByCode(admin, "PHONE_VALIDATE");
        enable(admin, orgId, svc.path("id").asText(), null);
        assertThat(status(track(orgId, "PHONE_VALIDATE", 3, 3))).isEqualTo(200);
        String invoiceId = generateInvoice(admin, orgId, monthStart(), monthEnd()).path("id").asText();

        MvcResult send = post(BILLING + "/invoices/" + invoiceId + "/send", admin, null);
        assertThat(status(send)).isEqualTo(400);
        assertThat(body(send).path("message").asText()).startsWith("Failed to send invoice");
        assertThat(data(get(BILLING + "/invoices/" + orgId, admin)).get(0).path("status").asText()).isEqualTo("DRAFT");

        MvcResult missing = post(BILLING + "/invoices/" + UUID.randomUUID() + "/send", admin, null);
        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing).path("message").asText()).isEqualTo("Invoice not found");
    }

    @Test
    @DisplayName("generating with no usage in the period (or for an unknown org) is refused, and nothing is written")
    void generateWithoutUsage() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin, true).path("id").asText();

        MvcResult none = post(BILLING + "/invoices/generate", admin,
            Map.of("organizationId", orgId, "periodStart", monthStart().toString(), "periodEnd", monthEnd().toString()));
        assertThat(status(none)).isEqualTo(400);
        assertThat(body(none).path("message").asText()).isEqualTo("No usage found for billing period");
        assertThat(data(get(BILLING + "/invoices/" + orgId, admin)).size()).isZero();

        MvcResult unknown = post(BILLING + "/invoices/generate", admin,
            Map.of("organizationId", UUID.randomUUID().toString(), "periodStart", "2000-01-01", "periodEnd", "2000-01-31"));
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).path("message").asText()).startsWith("Organization not found");

        MvcResult badDate = post(BILLING + "/invoices/generate", admin,
            Map.of("organizationId", orgId, "periodStart", "01/01/2000", "periodEnd", "2000-01-31"));
        assertThat(status(badDate)).isEqualTo(400);
        assertThat(code(badDate)).isEqualTo("MALFORMED_REQUEST");

        assertThat(data(get(BILLING + "/dashboard/" + orgId, admin)).path("currentMonthCost").decimalValue())
            .isEqualByComparingTo("0");
    }
}
