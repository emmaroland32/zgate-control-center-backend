-- Single sign-on: bind an operator to the identity provider's immutable subject.
--
-- Email alone is not a stable identifier. Addresses get reassigned when people leave, and shared
-- aliases become mailboxes; matching on email alone would hand a departed operator's role to
-- whoever next holds the address. The subject is linked on first SSO sign-in and checked
-- thereafter.
--
-- Nullable: password-only operators never have one, and existing rows must keep working.
ALTER TABLE control_center_users
    ADD COLUMN IF NOT EXISTS oidc_subject VARCHAR(255);

-- One operator per subject. Partial, so the many NULLs (password-only accounts) do not collide.
CREATE UNIQUE INDEX IF NOT EXISTS ux_control_center_users_oidc_subject
    ON control_center_users (oidc_subject)
    WHERE oidc_subject IS NOT NULL;
