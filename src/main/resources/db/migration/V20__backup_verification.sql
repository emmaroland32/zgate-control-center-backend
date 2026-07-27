-- Backup verification: the agent proves a completed backup is genuinely restorable (decrypts +
-- pg_restore --list) and reports the result here. Control Center can't verify itself (zero-knowledge)
-- — it only records the outcome, so a broken/undecryptable backup is visible before it's needed.

ALTER TABLE backup_record ADD COLUMN IF NOT EXISTS verified     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE backup_record ADD COLUMN IF NOT EXISTS verified_at  TIMESTAMP;
ALTER TABLE backup_record ADD COLUMN IF NOT EXISTS verify_error VARCHAR(512);
