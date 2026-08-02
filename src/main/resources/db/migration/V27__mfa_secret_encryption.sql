-- ============================================================================
-- Encrypt TOTP secrets at rest
-- ============================================================================
-- A stored TOTP secret is a code generator: anyone reading the Control Center
-- database (a dump, a backup, a read replica) could mint valid second factors
-- for every operator, permanently and undetectably — making the second factor
-- a function of the first factor's datastore.
--
-- Secrets are now AES-256-GCM envelopes under a subkey derived for the
-- 'mfa-secret' purpose (SecretCipher), so they share no key material with the
-- cloud-credential store. The key id is kept in its own column, matching the
-- credential store's convention: a rotation can be reasoned about with SQL
-- rather than by decrypting every row.
--
-- NULL mfa_key_id means the row predates encryption and holds plaintext. Those
-- rows keep working (login must not break) and are re-encrypted in place on the
-- owner's next successful code verification.
-- ============================================================================

ALTER TABLE control_center_users ADD COLUMN mfa_key_id VARCHAR(64);

-- Envelope + base64 is longer than the raw 32-char base32 secret.
ALTER TABLE control_center_users ALTER COLUMN mfa_secret         TYPE VARCHAR(512);
ALTER TABLE control_center_users ALTER COLUMN mfa_pending_secret TYPE VARCHAR(512);
