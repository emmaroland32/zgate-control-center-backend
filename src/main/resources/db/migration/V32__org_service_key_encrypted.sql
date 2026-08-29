-- Store the org's machine-to-machine service key encrypted-at-rest (in addition to its SHA-256 hash),
-- so provisioning can inject the raw key into a stack on every terraform run. A `terraform apply`
-- re-renders the tfvars from the stored spec and cannot recover a secret that was only present at
-- plan time, so the previously provisioned fleet came up with no CONTROLCENTER_SERVICE_KEY at all and
-- enforcement could never be turned on. The ciphertext is an AES-256-GCM envelope produced by
-- SecretCipher (purpose "service-key"); the key id records which master key encrypted it.
--
-- Additive and nullable: existing rows keep working (hash-only) until their next provision re-mints
-- and stores the encrypted copy. A DB dump without the SecretCipher master key cannot read these.

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS service_api_key_enc     TEXT;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS service_api_key_enc_kid VARCHAR(64);
