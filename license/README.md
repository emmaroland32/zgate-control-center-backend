# Licence signing key

`control-center-license-private.pem` is **deliberately not in git** and must be provided out of
band on every machine that issues licences.

Without it the application still starts: `LicenseSigningService.isSigningAvailable()` returns false
and bundle generation is skipped with a warning. Licence *issue* records are written, but no signed
bundle is produced, so an org will never activate. If you see that warning, this file is missing.

Point at it with `CONTROLCENTER_LICENSE_PRIVATE_KEY_PATH` (defaults to
`license/control-center-license-private.pem`).

## Why it is not tracked

This key signs every ZGATE licence in the fleet. Anyone holding it can mint a valid licence for any
customer, version, expiry and deployment tier — which nullifies the version gate (Layer 2), the
subscription kill switch (Layer 3) and image-pull authorization (Layer 4) at once, since all three
assume Control Center is the only party that can sign.

It was tracked from the `nexus` → `control-center` migration until 2026-08-02 and reached the public
GitHub remote. It is untracked from that date, but **it remains in git history**, so the key must be
treated as compromised and rotated before production. Rotation invalidates every licence already
issued, so it has to be a coordinated re-issue to every deployment — not a drop-in replacement.

The matching public key is embedded in the ZGATE backend, so a rotation changes both sides.
