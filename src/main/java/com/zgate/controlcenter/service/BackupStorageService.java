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

    /** SSE mode the presigned PUT bakes in; the agent must echo it as x-amz-server-side-encryption. */
    private static final String SSE = ServerSideEncryption.AES256.toString();

    private volatile S3Presigner presigner;
    private volatile S3Client s3;

    public BackupStorageService(
            @Value("${controlcenter.backup.enabled:false}") boolean enabled,
            @Value("${controlcenter.backup.s3.bucket:}") String bucket,
            @Value("${controlcenter.backup.s3.region:eu-west-2}") String region,
            @Value("${controlcenter.backup.presignTtlMinutes:30}") int presignTtlMinutes) {
        this.enabled = enabled;
        this.bucket = bucket == null ? "" : bucket.trim();
        this.region = region;
        this.presignTtl = Duration.ofMinutes(Math.max(1, presignTtlMinutes));
        if (isEnabled()) {
            log.info("Managed backup storage enabled (bucket={}, region={}, presignTtl={}m)",
                    this.bucket, region, presignTtlMinutes);
        }
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
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket).key(key)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .build();
        String url = presigner().presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(put)
                .build()).url().toString();
        // SSE is signed into the URL, so the agent MUST send this header on the PUT or S3 rejects it.
        return new PresignedUpload(url, Map.of("x-amz-server-side-encryption", SSE),
                LocalDateTime.now().plus(presignTtl));
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
