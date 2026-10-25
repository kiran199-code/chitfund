# chitfund-portal-service: build status (Steps 1 and 2 of the member journey)

Customer-facing portal back end. Separate Spring Boot service (port 8087, Java 17, Spring Boot 3.3.2), own Postgres schema `portal`
(Flyway history table `portal_schema_history`), no foreign keys to chitfund-service. It reads groups from chitfund-service over REST.

## What is built

**Customer web app (added after Steps 1 and 2).** A small React app in `frontend/`, built into the jar by the Dockerfile and served by this service
itself (so the session cookie is same-origin). Pages: sign-in with the Google button; `/invite/<token>` (sign in, then review the group and decline);
a signed-in home page; sign out. "Accept and join" is shown but disabled until Step 3. The page reads the Google client id from
`GET /api/v1/portal/config` at runtime. Checked: `tsc` clean and `vite build` succeeds. NOT checked: never opened in a browser, never run against
real Google, the Docker build of the UI stage, and the forward from `/invite/<token>` to the app (`SpaController`).

To use it on Render: set `PORTAL_BASE_URL` and `PORTAL_ALLOWED_ORIGINS` to this service's own https address (no trailing slash), and add that
address under "Authorized JavaScript origins" on the Google OAuth client.

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

## Step 3: accept and enrol (invite many people by e-mail)

- The e-mail is the customer's key; the customer id is internal. chitfund-service keeps the customer master (`chit_customer`, V8; `/api/v1/chit/customers/resolve|lookup|{id}`).
  Inviting looks up existing customers by e-mail (best effort) and stores the id on the invitation. An admin may also give an old customer id with the e-mail:
  on accept the e-mail is attached to that id (refused if the id already has another e-mail).
- Member accepts (`POST /api/v1/portal/invitations/{token}/accept`): the e-mail becomes a customer (existing, linked or new). If the group's enrolment quote asks
  for nothing (no cash, no lien, no guarantor) the tickets are enrolled at once and the invitation is ACCEPTED. Otherwise it goes to ACCEPTED_AWAITING_PAYMENT and
  the admin records the receipt: `POST /api/v1/portal/admin/groups/{code}/invitations/{id}/enrol` ("Record fees and enrol" in the console), until Step 4 payments.
- Each ticket has its own idempotency key `PORTAL-ENROL:<invitation>:<seq>`, so a retry after a crash or lost reply never makes a second ticket. The first ticket decides
  success; later tickets of a multi-ticket invitation are best effort and the admin can top up ("Enrol the rest").
- V2 also fixes V1: `status` was VARCHAR(24) but `ACCEPTED_AWAITING_PAYMENT` is 25 characters, so that status could never be stored (found by the harness).
- Admin console: "Invite by e-mail" panel on a group open for enrolment (one person per line: e-mail, name, tickets, old customer id) with the invitation list, resend,
  withdraw and enrol; the manual Enrol dialog now takes the customer e-mail first and fills in or creates the customer id.
- Verified here: 55 portal unit tests, 177 portal harness checks against PostgreSQL 16 (with a fake chitfund-service), 37 schema checks, chitfund-service 273 unit tests and 26 customer
  harness checks, both frontends typecheck. NOT verified: the admin Java (PortalClient, PortalInviteController, their test), the Spring wiring, the real HTTP calls between services,
  and the screens in a browser. A `Invitee` record with two constructors must deserialise through Jackson's canonical constructor: confirm with a real request.
- Needs on Render: admin service `PORTAL_SERVICE_URL`, `PORTAL_ADMIN_PASSWORD` (same value as the portal's), `PORTAL_ADMIN_USERNAME`; portal `CHIT_SERVICE_URL` and credentials.

## Customer Portal v2 (sign-in, dashboard, tickets, payments, auctions, approvals, offers)

- Sign-in is Google only (unchanged). A signed-out visitor sees the sign-in card and the offers staff marked PUBLIC. A signed-in member gets pages Dashboard, My tickets,
  Payments, Auctions, Approvals, Offers (address after `#`, e.g. `/#/auctions`). A sign-in that is not a customer of the chit house sees a "No chit account yet" page.
- Member API under `/api/v1/portal/me/`: `GET dashboard|tickets|payments|auctions|requests`, `POST bids`, `POST bids/withdraw` (needs `Idempotency-Key`). The customer id is NEVER
  taken from the request: it is looked up from the signed-in e-mail (`lookupCustomers`) on every call. A bid is checked against the member's own auction list first (live, `canBid`,
  ticket among their `eligibleTickets`); a withdrawal against their own valid bids. A ticket or bid that is not theirs gets the same refusal as one that is not allowed
  (`BID_NOT_ALLOWED`, 409). The chit-service idempotency key is `PORTAL-BID:<customerId>:<browser key>`, so two members can never collide. Bids and withdrawals are audited.
- chitfund-service gained read-only customer-scoped endpoints `/api/v1/chit/customers/{id}/tickets|payments|requests|auctions` and a `customerId` filter on `/dues`
  (permission `chit.customer.portfolio.read`, see IAM_PERMISSIONS.txt). Bids go through the existing bid endpoints and rules.
- Offers: table `portal.portal_offer` (V3), admin API `/api/v1/portal/admin/offers` (create, update, hide, show), public `GET /api/v1/portal/offers/public`; members also get them on the
  dashboard. Text and http(s) links only (no uploads); the server rejects `javascript:`/`data:` links and the page checks again. Admin console: Customer portal > Offers (CHIT_OPS).
- Deviations from what you asked, by design of the existing system: (1) auctions have no closing time (staff open and close them), so the page shows state and date, with no countdown, and refreshes
  every 8 seconds while one is live and the tab is visible; (2) "approvals" is a read-only list of requests involving the member (transfers, prize payouts): chitfund-service has no
  member-consent step, so there is nothing for a member to approve or reject yet (that needs a new workflow); (3) payments are view-only, the organiser records receipts.
- Verified here: 70 portal unit tests (incl. cross-member isolation with fakes), 192 portal harness checks on PostgreSQL 16 (incl. offers SQL), chitfund-service 273 unit tests and 228 harness
  checks (59 portfolio), both frontends typecheck, the member pages ran in a headless browser against a mocked API (no console errors; a bid was placed and shown), phone and desktop screenshots looked right.
- NOT verified: the admin Java (PortalOfferController and its test), Spring wiring of the new controllers, real HTTP between portal and chit service, Jackson reading the real chit JSON into the portal
  records (they ignore unknown fields; a renamed field would show as a missing value), the screens against the real API, Google sign-in in a browser.

## Honest Savers branded portal (redesign of Customer Portal v2)

- New look from the approved wireframes: brand name and tagline "Honest dealings. Happy savers.", logo mark, illustrations and icons drawn as inline SVG (no image files), warm green/marigold palette,
  Fraunces and Nunito Sans from Google Fonts. Landing page for visitors (sign in, public offers, how a chit works), top menu on desktop and a five-item bottom bar on phones (Home, My chits, Payments,
  Auctions, More). "Approvals" is now "Waiting on you". All earlier features are kept (sign-in/out, invitations accept/decline and their error states, dashboard, chits, payments, auctions with bidding, offers).
- New: organiser-editable text (table `portal_setting`, V4): how to pay (signed-in members only, on Payments), help phone, help hours, office address (footer on every page). Edited in the admin console
  under Customer portal > Offers & texts. API: public `GET /api/v1/portal/site`, admin `GET|POST /api/v1/portal/admin/settings`. Quick bid amounts (+500, +1,000, +5,000 over the best bid) shown when the best bid is visible.
- The logo, colours and pictures are my own drawings and a first proposal, not a supplied brand kit; swap `frontend/src/brand.tsx` and the CSS variables at the top of `styles.css` to change them. Real photos need
  an image-slot feature or files added to the app; none are included.
- Verified here: 73 portal unit tests, 201 portal harness checks (PostgreSQL 16, incl. settings SQL), frontend typecheck and production build, desktop and phone screenshots against a mocked API (no console errors
  except the expected 401 when signed out). NOT verified: admin Java (PortalSettingsController and test), Spring wiring, Google fonts and Google sign-in in a real browser, real data, dark mode (not provided: the page is light only),
  screen-reader and keyboard use beyond basic semantics, Telugu/Hindi (English only).

## Not started

Online payments, member approve/reject workflow, notifications and batch reminders.

## Run

    /home/claude/build_portal.sh          # compile + unit tests (sandbox only)
    harness/run.sh [invite|lifecycle|mail|auth|invitee|enrol|offers]   # needs the sandbox Postgres on port 5544
