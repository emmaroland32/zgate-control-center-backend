package com.zgate.controlcenter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * S3 backend for the managed-backup module. Control Center never streams the bytes itself: it hands
 * the org instance a short-lived <b>presigned URL</b> so the agent uploads/downloads directly to S3,
 * and the object is written with SSE. Credentials come from the standard AWS provider chain (instance
 * role / env) — no secrets here. Entirely inert unless {@code controlcenter.backup.enabled=true} and
 * a bucket is set; the S3 clients are built lazily so the app boots fine without AWS configured.
 */
@Service
@Slf4j
public class BackupStorageService {

    private final boolean enabled;
    private final String bucket;
    private final String region;
    private final Duration presignTtl;

    /**
     * Optional customer-managed KMS key id/ARN. Blank ⇒ SSE-S3 (AES256, current default). When set, the
     * object is written with SSE-KMS under this key, giving per-tenant key access control and a CloudTrail
     * audit record of every decrypt — a compliance win for the managed offering. The uploaded payload is
     * already client-side encrypted regardless, so SSE is defense-in-depth either way.
     */
    private final String kmsKeyId;

    private volatile S3Presigner presigner;
    private volatile S3Client s3;

    public BackupStorageService(
            @Value("${controlcenter.backup.enabled:false}") boolean enabled,
            @Value("${controlcenter.backup.s3.bucket:}") String bucket,
            @Value("${controlcenter.backup.s3.region:eu-west-2}") String region,
            @Value("${controlcenter.backup.s3.kmsKeyId:}") String kmsKeyId,
            @Value("${controlcenter.backup.presignTtlMinutes:30}") int presignTtlMinutes) {
        this.enabled = enabled;
        this.bucket = bucket == null ? "" : bucket.trim();
        this.region = region;
        this.kmsKeyId = kmsKeyId == null ? "" : kmsKeyId.trim();
        this.presignTtl = Duration.ofMinutes(Math.max(1, presignTtlMinutes));
        if (isEnabled()) {
            log.info("Managed backup storage enabled (bucket={}, region={}, presignTtl={}m, sse={})",
                    this.bucket, region, presignTtlMinutes, usingKms() ? "aws:kms" : "AES256");
        }
    }

    /** True when a customer-managed KMS key is configured (SSE-KMS); otherwise SSE-S3. */
    private boolean usingKms() {
        return !kmsKeyId.isBlank();
    }

    /** Configured = feature flag on AND a bucket name present. */
    public boolean isEnabled() {
        return enabled && !bucket.isBlank();
    }

    public Duration presignTtl() {
        return presignTtl;
    }

    /** Per-tenant object key: org/<orgId>/<backupId>.enc */
    public String objectKey(UUID orgId, UUID backupId) {
        return "org/" + orgId + "/" + backupId + ".enc";
    }

    /** A presigned PUT the agent uses to stream ciphertext straight to S3. */
    public record PresignedUpload(String url, Map<String, String> requiredHeaders, LocalDateTime expiresAt) {}

    public PresignedUpload presignedUpload(String key) {
        requireEnabled();
        PutObjectRequest.Builder put = PutObjectRequest.builder().bucket(bucket).key(key);
        // SSE is signed into the URL, so the agent MUST echo these headers on the PUT or S3 rejects it.
        Map<String, String> requiredHeaders;
        if (usingKms()) {
            put.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
            requiredHeaders = Map.of(
                    "x-amz-server-side-encryption", ServerSideEncryption.AWS_KMS.toString(),
                    "x-amz-server-side-encryption-aws-kms-key-id", kmsKeyId);
        } else {
            put.serverSideEncryption(ServerSideEncryption.AES256);
            requiredHeaders = Map.of("x-amz-server-side-encryption", ServerSideEncryption.AES256.toString());
        }
        String url = presigner().presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(put.build())
                .build()).url().toString();
        return new PresignedUpload(url, requiredHeaders, LocalDateTime.now().plus(presignTtl));
    }

    /** A presigned GET the agent uses to download ciphertext for a restore. */
    public String presignedDownload(String key) {
        requireEnabled();
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner().presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .getObjectRequest(get)
                .build()).url().toString();
    }

    /** Purge an object (retention/quota eviction). Best-effort; missing objects are ignored. */
    public void delete(String key) {
        if (!isEnabled()) return;
        try {
            s3().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException ignored) {
            // already gone
        } catch (RuntimeException e) {
            log.warn("Failed to delete backup object {}: {}", key, e.getMessage());
        }
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "Managed backup is not configured (set controlcenter.backup.enabled=true and controlcenter.backup.s3.bucket).");
        }
    }

    private S3Presigner presigner() {
        S3Presigner p = presigner;
        if (p == null) {
            synchronized (this) {
                p = presigner;
                if (p == null) {
                    p = presigner = S3Presigner.builder().region(Region.of(region)).build();
                }
            }
        }
        return p;
    }

    private S3Client s3() {
        S3Client c = s3;
        if (c == null) {
            synchronized (this) {
                c = s3;
                if (c == null) {
                    c = s3 = S3Client.builder()
                            .region(Region.of(region))
                            .httpClient(UrlConnectionHttpClient.create())
                            .build();
                }
            }
        }
        return c;
    }
}
