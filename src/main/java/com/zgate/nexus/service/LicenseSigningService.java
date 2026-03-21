package com.zgate.nexus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.nexus.domain.License;
import com.zgate.nexus.exception.NexusException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs license bundles with the Nexus RSA private key, producing the
 * {@code zgate-license-v2} format that org-side {@code LicenseService}
 * can verify and activate.
 *
 * <pre>
 * {
 *   "format":    "zgate-license-v2",
 *   "payload":   "<base64(payloadJson)>",
 *   "signature": "<base64(RSA-SHA256(payload))>"
 * }
 * </pre>
 *
 * The payload JSON contains licenseId, customer, expiresAt, fingerprint,
 * and the list of licensed modules.
 */
@Service
@Slf4j
public class LicenseSigningService {

    @Value("${nexus.license.privateKeyPath:license/nexus-license-private.pem}")
    private String privateKeyPath;

    private PrivateKey privateKey;

    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            this.privateKey = loadPrivateKey();
            log.info("License signing key loaded from: {}", privateKeyPath);
        } catch (Exception e) {
            log.warn("License private key not available at '{}'. Bundle generation will be disabled until the key is provided. Cause: {}",
                    privateKeyPath, e.getMessage());
        }
    }

    public boolean isSigningAvailable() {
        return privateKey != null;
    }

    /**
     * Build a signed {@code zgate-license-v2} bundle for the given license.
     * The resulting string can be saved as a {@code .lic} file and delivered
     * to the org server for activation.
     */
    public String generateSignedBundle(License license, String orgName) {
        if (privateKey == null) {
            throw new NexusException("License signing key not loaded. " +
                "Set NEXUS_LICENSE_PRIVATE_KEY_PATH to the RSA private key PEM file.");
        }

        try {
            // Build payload
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("licenseId", license.getId().toString());
            payload.put("customer", orgName != null ? orgName : "Organization-" + license.getOrganizationId());
            payload.put("expiresAt", license.getExpiresAt() != null
                ? license.getExpiresAt().format(DateTimeFormatter.ISO_DATE_TIME) + "Z"
                : "2099-12-31T23:59:59Z");
            payload.put("fingerprint", license.getFingerprint()); // may be null = unbound
            payload.put("maxUsers", license.getMaxUsers() != null ? license.getMaxUsers() : 9999);
            payload.put("features", license.getFeatures() != null ? license.getFeatures() : "all");

            // Single-module bundle — one License row per module in Nexus
            payload.put("modules", List.of(buildModuleEntry(license)));

            // Base64-encode payload JSON
            String payloadJson = mapper.writeValueAsString(payload);
            String payloadB64 = Base64.getEncoder().encodeToString(
                    payloadJson.getBytes(StandardCharsets.UTF_8));

            // RSA-SHA256 sign the base64 payload (same approach as org verifies)
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(payloadB64.getBytes(StandardCharsets.UTF_8));
            String signatureB64 = Base64.getEncoder().encodeToString(sig.sign());

            // Assemble final bundle
            Map<String, String> bundle = new LinkedHashMap<>();
            bundle.put("format", "zgate-license-v2");
            bundle.put("payload", payloadB64);
            bundle.put("signature", signatureB64);

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle);

        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException("Failed to generate signed license bundle: " + e.getMessage(), e);
        }
    }

    /**
     * Generate a single signed bundle covering multiple licenses (all must belong
     * to the same org). Uses the first license's metadata (expiresAt, fingerprint)
     * as the bundle-level fields, and adds one modules[] entry per license.
     */
    public String generateSignedBundleForModules(List<License> licenses, String orgName) {
        if (privateKey == null) {
            throw new NexusException("License signing key not loaded.");
        }
        if (licenses == null || licenses.isEmpty()) {
            throw new NexusException("No licenses provided for bundle generation.");
        }

        try {
            License primary = licenses.get(0);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("licenseId", primary.getId().toString());
            payload.put("customer", orgName != null ? orgName : "Organization-" + primary.getOrganizationId());
            payload.put("expiresAt", primary.getExpiresAt() != null
                ? primary.getExpiresAt().format(DateTimeFormatter.ISO_DATE_TIME) + "Z"
                : "2099-12-31T23:59:59Z");
            payload.put("fingerprint", primary.getFingerprint());
            payload.put("maxUsers", primary.getMaxUsers() != null ? primary.getMaxUsers() : 9999);
            payload.put("features", primary.getFeatures() != null ? primary.getFeatures() : "all");

            payload.put("modules", licenses.stream()
                .map(this::buildModuleEntry)
                .collect(java.util.stream.Collectors.toList()));

            String payloadJson = mapper.writeValueAsString(payload);
            String payloadB64 = Base64.getEncoder().encodeToString(
                    payloadJson.getBytes(StandardCharsets.UTF_8));

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(payloadB64.getBytes(StandardCharsets.UTF_8));
            String signatureB64 = Base64.getEncoder().encodeToString(sig.sign());

            Map<String, String> bundle = new LinkedHashMap<>();
            bundle.put("format", "zgate-license-v2");
            bundle.put("payload", payloadB64);
            bundle.put("signature", signatureB64);

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle);

        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException("Failed to generate signed license bundle: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildModuleEntry(License license) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("moduleId", license.getModuleName());
        m.put("maxUsers", license.getMaxUsers() != null ? license.getMaxUsers() : 9999);
        m.put("features", license.getFeatures() != null ? license.getFeatures() : "all");
        return m;
    }

    // ----------------------------------------------------------------
    // Private key loading
    // ----------------------------------------------------------------

    private PrivateKey loadPrivateKey() throws Exception {
        byte[] keyBytes = readKeyFile();
        String pem = new String(keyBytes, StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private byte[] readKeyFile() throws IOException {
        // Try as filesystem path first, then classpath
        java.nio.file.Path path = Paths.get(privateKeyPath);
        if (Files.exists(path)) {
            return Files.readAllBytes(path);
        }
        // Classpath fallback
        try (var is = getClass().getClassLoader().getResourceAsStream(privateKeyPath)) {
            if (is == null) throw new IOException("Key not found on classpath: " + privateKeyPath);
            return is.readAllBytes();
        }
    }
}
