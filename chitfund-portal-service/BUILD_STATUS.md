# chitfund-portal-service: build status (Steps 1 and 2 of the member journey)

Customer-facing portal back end. Separate Spring Boot service (port 8087, Java 17, Spring Boot 3.3.2), own Postgres schema `portal`
(Flyway history table `portal_schema_history`), no foreign keys to chitfund-service. It reads groups from chitfund-service over REST.

## What is built

- **Step 1, invitations.** Admin invites a batch by email (up to 200), lists, resends and revokes. 256-bit link token, only its SHA-256 hash is
  stored. Statuses PENDING, ACCEPTED_AWAITING_PAYMENT, ACCEPTED, DECLINED, EXPIRED, REVOKED; expiry is evaluated lazily and swept by a scheduler.
  One live invitation per group and address. Batch invite is idempotent (`Idempotency-Key`). Mail goes through a transactional outbox with
  retry (1 min, 5 min, 30 min, 2 h, 6 h; give-up after `max-attempts`, shown to the admin as SEND_FAILED).
- **Step 2, Google sign-in and sessions.** The browser sends the Google ID token; the server verifies it, then issues its own random session
  token (hash stored, HttpOnly cookie). No passwords anywhere. Every POST needs `X-Portal-Client: web` and an allowed Origin. Rate limiting on sign-in.
  The invitee can view and decline an invitation, only from the Google account whose address it was sent to.
- Console mail sender by default; SMTP sender selected with `CHIT_PORTAL_MAIL_MODE=SMTP` and the usual mail environment variables.

API: admin `POST/GET /api/v1/portal/admin/groups/{code}/invitations`, `POST .../{id}/resend`, `POST .../{id}/revoke` (HTTP Basic);
member `POST /api/v1/portal/auth/google`, `POST /auth/logout`, `GET /me`, `GET /invitations/{token}`, `POST /invitations/{token}/decline`.

## How it was checked (and what that does and does not prove)

The build sandbox cannot reach Maven Central, so nothing here was compiled against real Spring. Compilation used hand-written stand-ins for the
Spring/JUnit APIs the code touches and the real Jackson and SLF4J jars.

| Check | Result |
|---|---|
| Pure unit tests (`/home/claude/build_portal.sh`): lifecycle, email and token rules, Google claim policy, CSRF/origin guard, rate limiter, cookies, mail rendering, retry policy | 50 passed |
| V1 migration on PostgreSQL 16 | applies cleanly |
| `scripts/verify_schema.sql`: constraint and index behaviour, rolled back | 30 checks passed |
| `harness/run.sh`: the real service classes (invites, outbox, sign-in, invitee view) on real PostgreSQL, with fakes for Google, chitfund-service and the mailer | 109 passed |

The harness covers: batch invite with each skip reason, idempotent replay and key reuse, group not found / not open / full / service down,
resend (old link dies), revoke, expiry (lazy and swept), re-invite after revoke / decline / expiry, outbox dispatch, redaction, retry, back-off
and give-up, sign-in matching (new, same account, claim by address, different account refused, address change at Google, blocked), sessions
(resolve, expire, sign-out), invitee view / decline, mismatch refusal, neutral errors for dead links.

A defect found by the harness and fixed: refusal audit rows (EMAIL_MISMATCH, SIGN_IN_REFUSED) were written and then rolled back with the
exception. `signIn`, `view` and `decline` now use `@Transactional(noRollbackFor = PortalException.class)`. Nothing else is written before those refusals.

## NOT verified. Please treat as untested

- **Google token verification.** `NimbusGoogleIdentityVerifier` (the Nimbus decoder calls), the Google claim names, the issuer spellings and the JWKS
  URL are written from memory and compiled only against a stand-in. Never run against Google. Marked VERIFY BEFORE USE. Check against current
  Google and Spring Security docs, then try a real sign-in.
- Controllers, Spring wiring, `SecurityConfig`, `ApiExceptionHandler`, `@Scheduled` jobs, the SMTP sender, Flyway through Spring, the pom and the Dockerfile
  have never been built or started. First `mvn verify` on your machine is the real test. Expect some fixes.
- The harness models `@Transactional` by hand; it proves the service logic, not that the annotations are applied.
- The `noRollbackFor` attribute is from memory of the Spring API; confirm it compiles.

## Known limitations and decisions to confirm

- The raw invite link sits in the outbox until the mail is sent (then the body is blanked). A deviation from "no raw token stored".
- Delivery is at least once. The chitfund-service HTTP call runs inside the invite transaction.
- Email matching follows Google's Gmail rule: lower-case and trim; for gmail.com and googlemail.com dots and `+tag` are dropped and googlemail.com
  becomes gmail.com. Other domains (including Google Workspace) are kept exactly. The rule is from memory of Google's documentation. Verify it. The invite
  mail goes to the canonical address, which reaches the same Gmail mailbox.
- Rate limiting is in memory, per instance.
- Plain-text mail only. Group rules shown to the invitee are the summary only (spec P-5 full rules still to do). Revoke takes no idempotency key.
- Seats are not reserved by an invitation (decision D1).

## Not started

Step 3 accept and enrol, Step 4 payments, Step 5 bids, Step 6 real notifications and batch reminders, Step 7 React UI.

## Run

    /home/claude/build_portal.sh          # compile + unit tests (sandbox only)
    harness/run.sh [invite|lifecycle|mail|auth|invitee]   # needs the sandbox Postgres on port 5544
