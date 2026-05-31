# FXBrief API Reference

This document is the primary API contract for the FXBrief backend. It is maintained
incrementally as new endpoints are added each phase. Frontend integration should treat
this document as authoritative — Swagger UI is a secondary view of the same contract.

## Conventions

### Base URL
All endpoints are served from the application root. There is no `/api/v1` prefix in v1.

### Response envelope
Every JSON response (success or error) uses the following envelope:

```json
{
  "success": true,
  "data": { },
  "error": { },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

- `success` — `true` if the request completed normally, `false` otherwise.
- `data` — present on success, omitted on error.
- `error` — present on error, omitted on success. Shape:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "fieldErrors": [
    { "field": "email", "message": "must be a well-formed email address" }
  ]
}
```

`fieldErrors` is present only when the error is a validation failure.

- `timestamp` — server-side UTC timestamp in ISO-8601.

The `/actuator/health` endpoint is the only documented exception; it follows Spring Boot's
native actuator response shape.

### Error codes

| Code                          | HTTP | Meaning |
|-------------------------------|------|---------|
| `VALIDATION_FAILED`           | 400  | Request body or query parameters failed validation. |
| `MALFORMED_REQUEST`           | 400  | Request body could not be parsed. |
| `EMAIL_DOMAIN_NOT_ALLOWED`    | 400  | Email belongs to a blocked disposable-email domain. |
| `INVALID_TOKEN`               | 400  | Verification or reset token was not found. |
| `TOKEN_EXPIRED`               | 400  | Verification or reset token has expired. |
| `TOKEN_ALREADY_USED`          | 400  | Verification or reset token has already been consumed. |
| `INVALID_GOOGLE_TOKEN`        | 400  | Google ID token failed verification (signature, expiry, audience, issuer, or `email_verified=false`). |
| `UNAUTHENTICATED`             | 401  | Authentication is required and was missing or invalid. |
| `INVALID_CREDENTIALS`         | 401  | Email and password combination did not match. |
| `ACCOUNT_NOT_VERIFIED`        | 403  | Account exists but email has not yet been verified. |
| `ACCOUNT_INACTIVE`            | 403  | Account is inactive for reasons other than unverified email. |
| `FORBIDDEN`                   | 403  | Authenticated but not authorised for this resource. |
| `NOT_FOUND`                   | 404  | Resource or route does not exist. |
| `METHOD_NOT_ALLOWED`          | 405  | HTTP method not allowed for this route. |
| `EMAIL_ALREADY_REGISTERED`    | 409  | An account with this email already exists. |
| `INVALID_PLAN_FOR_TOP_UP`     | 400  | Top-up target plan is neither `BASIC` nor `PREMIUM`. |
| `RATE_LIMIT_EXCEEDED`         | 429  | Too many requests from this IP within the rate-limit window. A `Retry-After` header indicates the number of seconds to wait. |
| `MARKET_DATA_UNAVAILABLE`     | 503  | Upstream OHLCV or economic-calendar provider call failed after retries; circuit breaker may be open. Introduced in Phase 3A; surfaced to clients via the report-generation endpoint in Phase 3B. |
| `NARRATIVE_UNAVAILABLE`       | 503  | Claude API call failed after retries; circuit breaker may be open. Introduced in Phase 3A; surfaced via Phase 3B. |
| `MARKET_DATA_NOT_READY`       | 503  | Pre-fetch has not yet produced a fully-complete cycle (cold start, or no `fetch_id` has full row coverage). Introduced in Phase 3A; surfaced via Phase 3B. Per PRD §10.2 the client retries without consuming a report credit. |
| `MARKET_CLOSED`               | 409  | Report generation attempted outside forex market hours (Friday 22:00 UTC → Sunday 22:00 UTC). Introduced in Phase 3B. |
| `DAILY_LIMIT_REACHED`         | 409  | A `user_reports` row already exists for the authenticated user on the current forex market date. Introduced in Phase 3B. |
| `NO_REMAINING_REPORTS`        | 409  | The authenticated user has zero remaining reports. Introduced in Phase 3B. |
| `USER_NOT_FOUND`              | 404  | Admin lookup targeted a user id that does not exist. Introduced in Phase 4A. |
| `CANNOT_MODIFY_ADMIN`         | 403  | Admin action targeted another admin user. Admin-on-admin mutation is disallowed to prevent accidental lock-out from the panel. Introduced in Phase 4A. |
| `INVALID_DATE_RANGE`          | 400  | Admin usage-overview filter has `from` later than `to`. Introduced in Phase 4A. |
| `FEEDBACK_NOT_FOUND`          | 404  | Admin reply targeted a feedback id that does not exist. Introduced in Phase 4B. |
| `FEEDBACK_ALREADY_REPLIED`    | 409  | Admin reply targeted feedback that has already been replied to. Introduced in Phase 4B. |
| `WEEKLY_SUMMARY_NOT_FOUND`    | 404  | Weekly summary id (admin) or published slug (public) does not exist. Introduced in Phase 4C. |
| `INVALID_WEEKLY_SUMMARY_STATUS` | 400 | A status value supplied to the admin list filter or update is not one of `DRAFT`, `PUBLISHED`, `ARCHIVED`. Introduced in Phase 4C. |
| `WEEKLY_SUMMARY_NOT_PUBLISHABLE` | 409 | Publish was attempted on a summary with no admin content. Introduced in Phase 4C. |
| `ARTICLE_NOT_FOUND`           | 404  | Article id (admin) or published slug (public) does not exist. Introduced in Phase 4D. |
| `INVALID_ARTICLE_STATUS`      | 400  | A status value supplied to the admin list filter, create, or update is not one of `DRAFT`, `SCHEDULED`, `PUBLISHED`, `ARCHIVED`. Introduced in Phase 4D. |
| `INVALID_ARTICLE_CATEGORY`    | 400  | A category value supplied to a filter, create, or update is not one of the six article categories. Introduced in Phase 4D. |
| `ARTICLE_SLUG_CONFLICT`       | 409  | The resolved slug already belongs to another article. Introduced in Phase 4D. |
| `ARTICLE_DELETE_NOT_ALLOWED`  | 400  | Hard delete attempted on an article that is not in `DRAFT` status. Introduced in Phase 4D. |
| `PAYMENT_NOT_AVAILABLE`       | 403  | Top-up attempted while in the Midtrans sandbox phase by a user without `payment_beta_access`. Introduced in Phase 5B. |
| `INVALID_PAYMENT_SIGNATURE`   | 401  | Midtrans webhook payload failed `SHA-512` signature verification. Introduced in Phase 5B. |
| `EXCHANGE_RATE_UNAVAILABLE`   | 503  | No USD/IDR rate is currently cached (cold start before the first successful fetch). Affects top-up creation and `GET /api/exchange-rate`. Introduced in Phase 5B. |
| `PAYMENT_INITIATION_FAILED`   | 503  | Midtrans Snap transaction creation failed after retries; circuit breaker may be open. Introduced in Phase 5B. |
| `INTERNAL_ERROR`              | 500  | Unhandled server error. Details written to logs only. |

Additional codes are introduced per phase as features are added.

### Authentication

Authenticated endpoints require a JWT in the `Authorization` header:

```
Authorization: Bearer <jwt>
```

Tokens are HS256-signed, issued on successful login (email or Google), and valid for 24
hours. There is no refresh-token flow in v1; clients re-authenticate when the token
expires.

### Rate limiting

A subset of unauthenticated auth endpoints is rate-limited per client IP using a fixed
60-second window:

| Endpoint                      | Limit per IP per minute |
|-------------------------------|-------------------------|
| `POST /auth/login`            | 5                       |
| `POST /auth/google`           | 5                       |
| `POST /auth/register`         | 3                       |
| `POST /auth/forgot-password`  | 3                       |

Exceeding the limit returns a `429 Too Many Requests` response with
`error.code = RATE_LIMIT_EXCEEDED` and a `Retry-After` header indicating the number of
seconds until the window resets. The window is fixed (not sliding); when it expires the
counter resets to zero.

Client IP is resolved from the leftmost entry in the `X-Forwarded-For` header when
present, falling back to the connection's remote address.

### CORS

The API accepts cross-origin requests from:

- `http://localhost:4200` — Angular development server.
- The value of `FRONTEND_BASE_URL` — the production frontend domain.

Allowed methods: `GET, POST, PUT, DELETE, OPTIONS`.
Allowed headers: `Authorization, Content-Type`.
Credentials are not allowed (authentication uses bearer tokens, not cookies).
Preflight responses are cached for 1 hour.

### Email notifications

Several endpoints trigger a transactional email as a **non-blocking side effect**
(PRD §10, Phase 5A). Email is never part of the request/response contract: it is
dispatched on a background thread after the triggering transaction commits, and
any send failure is logged to the `email_logs` table without altering the HTTP
response. No endpoint returns a different status, body, or error because an email
succeeded or failed. The seven events and their triggering endpoints:

| Event | Triggered by | Recipient |
|-------|--------------|-----------|
| Email verification | `POST /auth/register` | new user |
| Welcome | `POST /auth/verify-email`, and `POST /auth/google` (new Google user only) | user |
| Reports exhausted | `POST /reports/generate` when `reportsExhausted` is true | user |
| Feedback thank-you | `POST /feedback` | user |
| Feedback admin notification | `POST /feedback` | `ADMIN_NOTIFICATION_EMAIL` |
| Account deletion confirmation | `POST /auth/request-deletion` (first request only) | user |
| Feedback reply | `POST /admin/feedback/{feedbackId}/reply` | user |

The password-reset email is intentionally **not** part of this set; the reset
link remains log-only in v1. `email_logs` rows and the internal
`EMAIL_SEND_FAILED` marker are operational only and never appear on the wire.

## Endpoints

### `GET /actuator/health`

Health and readiness probe for the application and its database connection.

**Authentication:** none
**Request body:** none
**Response:** Spring Boot actuator native shape.

```json
{ "status": "UP" }
```

Status is `UP` when the application is reachable and the database connection is available.
Detailed component breakdown is intentionally hidden in production responses.

**Errors:** none. The endpoint always returns 200 with a `status` field. Operational tooling
should treat any status other than `UP` as unhealthy.

### `GET /v3/api-docs`

Returns the generated OpenAPI 3 document for the entire API.

**Authentication:** none
**Response:** OpenAPI 3 JSON document.

### `GET /swagger-ui.html`

Renders the Swagger UI for interactive API exploration. Served by springdoc-openapi.

**Authentication:** none
**Response:** HTML page.

### `POST /auth/register`

Registers a new user account.

**Authentication:** none

**Request body:**

```json
{
  "email": "alice@example.com",
  "password": "correct horse battery",
  "name": "Alice"
}
```

**Validation:**
- `email` — required, well-formed email, max 255 characters.
- `password` — required, 8 to 100 characters.
- `name` — required, non-blank, max 255 characters.

**Behaviour:**
- The email is lowercased before persistence and uniqueness check.
- If the email's domain is in the bundled disposable-email blocklist,
  `EMAIL_DOMAIN_NOT_ALLOWED` is returned.
- If an account with the same email already exists, `EMAIL_ALREADY_REGISTERED` is
  returned. This is returned uniformly regardless of how the existing account was
  created — the response does not disclose whether the email is associated with a
  password account or a Google account.
- The account is created with `is_active = false` and assigned the `USER` role.
- An email verification token is generated, persisted, and the resulting verification link is
  written to the application log at `INFO` level and emailed to the user via Resend
  (Phase 5A). The email is sent on a background thread after the registration
  transaction commits; a send failure is logged and does not affect the
  registration response (email is non-critical).

**Success response (201):**

```json
{
  "success": true,
  "data": {
    "userId": 42,
    "email": "alice@example.com",
    "message": "Registration successful. Please verify your email to activate your account."
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `400 VALIDATION_FAILED` — request validation failed.
- `400 EMAIL_DOMAIN_NOT_ALLOWED` — disposable-email domain.
- `409 EMAIL_ALREADY_REGISTERED` — email already in use.
- `429 RATE_LIMIT_EXCEEDED` — more than 3 registration attempts from this IP in the last minute.

### `POST /auth/verify-email`

Consumes an email verification token and activates the associated user account.

**Authentication:** none

**Request body:**

```json
{
  "token": "uA7s2..."
}
```

**Validation:**
- `token` — required, non-blank, max 255 characters.

**Behaviour:**
- The token is looked up by exact match (it is stored in plaintext).
- If the token is unknown, `INVALID_TOKEN` is returned.
- If the token has already been used, `TOKEN_ALREADY_USED` is returned.
- If the token is past its 24-hour TTL, `TOKEN_EXPIRED` is returned.
- On success, the user's `is_active` flag is set to `true` and the token's `used_at` is
  stamped with the current time.
- A welcome email is sent to the user via Resend (Phase 5A) after the
  verification transaction commits — explaining the Free plan, the 3 free
  reports, and how to top up. The email is background and non-critical; a send
  failure does not affect the verification response. (Google sign-up has no
  verification step, so the welcome email is sent at account-creation time on
  the `POST /auth/google` new-user path instead.)

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "message": "Email verified. Your account is now active."
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `400 VALIDATION_FAILED` — request validation failed.
- `400 INVALID_TOKEN` — token not found.
- `400 TOKEN_ALREADY_USED` — token already consumed.
- `400 TOKEN_EXPIRED` — token past its TTL.

### `POST /auth/login`

Authenticates a user with email and password and issues a JWT.

**Authentication:** none

**Request body:**

```json
{
  "email": "alice@example.com",
  "password": "correct horse battery"
}
```

**Validation:**
- `email` — required, well-formed email, max 255 characters.
- `password` — required, max 100 characters.

**Behaviour:**
- The email is lowercased before lookup.
- If no user exists, the stored password hash is null (account created via Google only),
  or the password does not match, `INVALID_CREDENTIALS` is returned. The response does
  not disclose which of these conditions applied.
- If the account exists but `is_active = false`:
  - If `deletion_requested_at` is null, `ACCOUNT_NOT_VERIFIED` is returned.
  - Otherwise `ACCOUNT_INACTIVE` is returned.
- On success, an HS256 JWT is issued with a 24-hour TTL. Claims: `sub` (user id), `email`,
  `role`, `iss` (`fxbrief`), `iat`, `exp`.
- If the user has a pending deletion (`deletion_requested_at` is set), `deletionPending`
  is `true` and `deletionDate` is `deletion_requested_at + 30 days`. The frontend uses
  this flag to show the Deletion Pending Modal before entering the app.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresAt": "2026-05-18T08:00:00Z",
    "userId": 42,
    "email": "alice@example.com",
    "name": "Alice",
    "role": "USER",
    "deletionPending": false
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

When `deletionPending` is `true`, `deletionDate` is also present:

```json
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresAt": "2026-05-18T08:00:00Z",
    "userId": 42,
    "email": "alice@example.com",
    "name": "Alice",
    "role": "USER",
    "deletionPending": true,
    "deletionDate": "2026-06-10T14:00:00Z"
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `400 VALIDATION_FAILED` — request validation failed.
- `401 INVALID_CREDENTIALS` — wrong email or password, or the account exists but has
  no password (Google-only). The response is the same in every case.
- `403 ACCOUNT_NOT_VERIFIED` — account exists but email is not yet verified.
- `403 ACCOUNT_INACTIVE` — account inactive for another reason.
- `429 RATE_LIMIT_EXCEEDED` — more than 5 login attempts from this IP in the last minute.

### `POST /auth/google`

Authenticates a user with a Google ID token and issues a JWT.

The frontend obtains a Google ID token via Google Identity Services using the
`GOOGLE_OAUTH_CLIENT_ID` Web client and posts it to this endpoint. The backend verifies
the token's signature, expiry, issuer, and audience against Google's published keys, then
applies the account linking and creation rules described below.

**Authentication:** none

**Request body:**

```json
{
  "idToken": "eyJhbGciOiJSUzI1NiI..."
}
```

**Validation:**
- `idToken` — required, non-blank, max 4096 characters.

**Behaviour:**

The ID token is verified before any database lookup. Verification rejects tokens with a
bad signature, an expired `exp`, an issuer other than `accounts.google.com` or
`https://accounts.google.com`, an audience other than the configured
`GOOGLE_OAUTH_CLIENT_ID`, or `email_verified = false`. Any verification failure returns
`INVALID_GOOGLE_TOKEN`.

The verified payload yields the Google subject (`sub`) and email. The handler then
resolves the account using a fixed precedence:

1. If an `oauth_accounts` row exists with `(provider = 'google', provider_user_id = sub)`,
   the linked user is used directly. This is the returning Google user path.
2. Otherwise, the user table is queried by email:
   - If a user with this email exists, a Google link is inserted for that user. If the
     user is inactive, `is_active` is set to `true` — Google has already verified the
     email so the unverified-email gate no longer applies. A user with a pending
     deletion remains pending; login still succeeds and surfaces `deletionPending`.
   - If no user exists, a new user is created with `is_active = true`, `password_hash`
     null, `role = USER`, and the Google link is inserted. A welcome email is sent
     to the new user via Resend (Phase 5A) — Google sign-up skips email
     verification, so the welcome email that an email-registered user receives
     after verification is sent here at creation time instead. The
     account-linking and returning-user paths do **not** send a welcome email.
     The send is background and non-critical.

On success, an HS256 JWT is issued identically to `POST /auth/login` (same claims, same
24-hour TTL). The response shape is identical to the email login response.

The local `users.email` column is set from the verified Google email on first creation
or first link and is not subsequently updated if the Google email later changes.

A new account starts with no subscription. Subscription provisioning will be wired in
Phase 2A.

**Success response (200):**

Identical to `POST /auth/login`. The `deletionPending` and `deletionDate` fields follow
the same rules.

**Errors:**
- `400 VALIDATION_FAILED` — request validation failed.
- `400 INVALID_GOOGLE_TOKEN` — Google ID token failed verification.
- `429 RATE_LIMIT_EXCEEDED` — more than 5 Google login attempts from this IP in the last minute.

### `POST /auth/forgot-password`

Initiates a password reset.

**Authentication:** none

**Request body:**

```json
{
  "email": "alice@example.com"
}
```

**Validation:**
- `email` — required, well-formed email, max 255 characters.

**Behaviour:**
- If no account exists for the email, the endpoint returns a generic success message
  without revealing whether the address is registered.
- If the account exists — whether or not it currently has a password — a 256-bit token is
  generated, its SHA-256 hash is persisted with a 30-minute TTL, and the resulting reset
  link is written to the application log at `INFO` level. The raw token is never returned
  to the client. The password-reset email is **not** part of the Phase 5A email set
  (PRD §10 enumerates seven transactional emails and the reset email is not among them),
  so the reset link remains retrievable via the application log only; it is not sent via
  Resend in v1.
- A Google-only account (no `password_hash`) is treated identically to any other account.
  Completing the reset sets a password and the account becomes dual-auth: subsequent
  email logins and Google logins both succeed.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "message": "If an account exists for this email, a password reset link has been sent."
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `400 VALIDATION_FAILED` — request validation failed.
- `429 RATE_LIMIT_EXCEEDED` — more than 3 forgot-password attempts from this IP in the last minute.

### `POST /auth/reset-password`

Completes a password reset using a token issued by `/auth/forgot-password`.

**Authentication:** none

**Request body:**

```json
{
  "token": "9f3a1c...",
  "newPassword": "new correct horse battery"
}
```

**Validation:**
- `token` — required, non-blank, max 255 characters.
- `newPassword` — required, 8 to 100 characters.

**Behaviour:**
- The token is hashed with SHA-256 and looked up against the stored `token_hash`.
- If the hash is unknown, `INVALID_TOKEN` is returned.
- If the token has already been used, `TOKEN_ALREADY_USED` is returned.
- If the token is past its 30-minute TTL, `TOKEN_EXPIRED` is returned.
- On success, the user's `password_hash` is rotated to BCrypt of the new password and
  the token's `used_at` is stamped with the current time. If the user previously had no
  password (Google-only account), the account is now dual-auth.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "message": "Password has been reset. You can now log in with your new password."
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `400 VALIDATION_FAILED` — request validation failed.
- `400 INVALID_TOKEN` — token not found.
- `400 TOKEN_ALREADY_USED` — token already consumed.
- `400 TOKEN_EXPIRED` — token past its TTL.

### `POST /auth/request-deletion`

Requests account deletion. The account enters a 30-day grace period after which it is
permanently removed by the daily hard-delete scheduler.

**Authentication:** required (Bearer JWT)

**Request body:** none

**Behaviour:**
- If the authenticated user has no pending deletion, `deletion_requested_at` is set to the
  current server time.
- If a deletion is already pending, the request is idempotent — the existing
  `deletion_requested_at` is returned unchanged, so repeated clicks do not reset the
  30-day clock.
- On the transition that newly sets `deletion_requested_at` (first request only), a
  deletion-confirmation email is sent to the user via Resend (Phase 5A) stating the
  scheduled deletion date and that logging in before that date cancels the deletion.
  An idempotent re-request does not re-send the email. The send is background and
  non-critical.
- `is_active` is **not** changed by this endpoint. The user can still log in during the grace
  period (login surfaces `deletionPending: true` and `deletionDate` so the frontend can
  render the Deletion Pending Modal). Cancellation is performed via
  `POST /auth/cancel-deletion`.
- Once the grace period has elapsed, the user, the user's verification tokens, the user's
  password-reset tokens, and the user's `oauth_accounts` rows are deleted from the
  database by the daily scheduler (`ON DELETE CASCADE`).

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "userId": 42,
    "email": "alice@example.com",
    "deletionRequestedAt": "2026-05-17T08:00:00Z",
    "deletionDate": "2026-06-16T08:00:00Z",
    "message": "Account deletion requested. Your account will be permanently deleted after the grace period unless cancelled."
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `404 NOT_FOUND` — the authenticated user could not be located.

### `POST /auth/cancel-deletion`

Cancels a pending account deletion.

**Authentication:** required (Bearer JWT)

**Request body:** none

**Behaviour:**
- The authenticated user's `deletion_requested_at` is cleared and `is_active` is set to
  `true`.
- The endpoint is idempotent: invoking it when no deletion is pending returns success
  with no observable state change.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "userId": 42,
    "email": "alice@example.com",
    "message": "Account deletion has been cancelled."
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `404 NOT_FOUND` — the authenticated user could not be located (e.g. already
  hard-deleted by the daily scheduler).

### `GET /subscription`

Returns the authenticated user's current subscription, the active plan, the effective plan
after applying the lapse rule, and the current forex-market open/closed status. Intended
as the primary dashboard payload.

**Authentication:** required (Bearer JWT)

**Request body:** none

**Behaviour:**
- Looks up the single `subscriptions` row keyed by the authenticated user id and projects it
  alongside the joined `subscription_plans` row.
- `plan` is the base plan persisted on the subscription row — the plan the user most
  recently paid for, or `FREE` for a never-paid user.
- `effectivePlan` is the plan the frontend should render as the user's current label and
  use to gate history access and report generation. It downgrades from the base plan to
  `FREE` when all of the following hold:
  1. The base plan is `BASIC` or `PREMIUM`.
  2. `remainingReports` is zero.
  3. No `subscription_usage` row exists for this user on the current forex market date
     (PRD §5.4: "0 remaining reports, next forex market day — plan label changes to Free,
     history locks, generation disabled"). Within the same forex market day the user
     generated their last report, `effectivePlan` continues to match the base plan.
  A top-up (Phase 5B) restores `remainingReports > 0` and `effectivePlan` reverts to the
  paid plan immediately.
- `hasEverPaid` is read from the `users` row. The Phase 5B Midtrans webhook flips it to
  `true` on the first successful payment and it is never cleared thereafter; for a newly
  registered user who has never paid the value is `false`.
- `marketOpen` is computed server-side from the JVM's UTC clock. It is `false` between
  Friday 22:00 UTC and Sunday 22:00 UTC (the forex weekend window) and `true` at all
  other times. The boolean is recomputed on every request — there is no caching.
- This endpoint does not consume or modify the report count.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "plan": {
      "id": 2,
      "name": "BASIC",
      "price": 10.00,
      "reportQuota": 20
    },
    "effectivePlan": {
      "id": 1,
      "name": "FREE",
      "price": 0.00,
      "reportQuota": 3
    },
    "remainingReports": 0,
    "hasEverPaid": true,
    "marketOpen": true
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

The example above shows a lapsed Basic user (paid Basic, ran out, new forex day has
started). For a non-lapsed user `plan` and `effectivePlan` reference the same plan row.

Plan `name` is one of `FREE`, `BASIC`, `PREMIUM`. `price` is a decimal in USD.
`reportQuota` is the report count granted by a fresh top-up of the plan (3 for Free, 20
for Basic and Premium).

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `404 NOT_FOUND` — no subscription exists for the authenticated user. In Phase 2A every
  registered user is provisioned with a Free subscription at registration time, so this
  should not occur for a normally-created account; it is documented for defence in depth.

### `GET /subscription/remaining`

Returns the authenticated user's remaining report count. Intended as a lightweight poll
endpoint when only the counter is needed (e.g. after a report is generated in a later
phase).

**Authentication:** required (Bearer JWT)

**Request body:** none

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "remainingReports": 3
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `404 NOT_FOUND` — no subscription exists for the authenticated user (see notes on
  `GET /subscription`).

### `POST /subscription/top-up`

Initiates a top-up for the authenticated user by creating a Midtrans Snap transaction
and returning the Snap token the frontend uses to open the Snap payment popup. The same
response carries the carry-over preview the frontend renders in the warning modal before
opening the popup.

**This endpoint does not change the subscription.** The plan change, report-count
mutation, and audit row are written only when Midtrans confirms the payment via the
webhook (see `POST /payment/webhook`). Creating the Snap transaction records a
`PENDING` row in `payment_transactions`; nothing in `subscriptions` is touched until a
verified successful callback arrives.

**Authentication:** required (Bearer JWT)

**Request body:**

```json
{
  "plan": "BASIC"
}
```

**Validation:**
- `plan` — required, non-blank, max 16 characters. Must be `BASIC` or `PREMIUM`
  (case-insensitive). `FREE` is not a valid top-up target.

**Behaviour:**
- The target plan code is validated. `BASIC` and `PREMIUM` are the only accepted values;
  anything else (including `FREE`) returns `INVALID_PLAN_FOR_TOP_UP`.
- **Beta gate.** While the deployment runs against the Midtrans sandbox
  (`MIDTRANS_IS_PRODUCTION=false`), only users with `payment_beta_access = true` may
  proceed; others receive `403 PAYMENT_NOT_AVAILABLE` with the message "Payment is not
  yet available. Please wait for the full launch." In production the flag is ignored and
  all authenticated users may pay.
- The authenticated user's current `subscriptions` row is read. `remainingReports` is
  preserved in the response as the carry-over base; per PRD §5.7, remaining reports
  always carry over and are never lost on a plan switch.
- `carryOverCalculation` is computed as `{ remainingReports, additionalReports, newTotal }`
  where `additionalReports` is the target plan's `report_count` (20 for both Basic and
  Premium) and `newTotal = remainingReports + additionalReports`.
- `warningFlag` is `true` when `remainingReports > 0`. The frontend uses this to decide
  whether to show the carry-over warning modal before opening the Snap popup (PRD §5.7).
  A Free user and a lapsed user with 0 remaining both receive `warningFlag = false`.
- The charge is priced in IDR. The current USD/IDR rate (see `GET /api/exchange-rate`) is
  read and the plan's USD price is converted to a whole-rupiah gross amount (rounded to
  the nearest rupiah). `exchangeRate` is the rate applied and `amountIdr` is the gross
  amount sent to Midtrans. The rate in effect at creation time is also stored on the
  transaction for audit.
- A Midtrans Snap transaction is created with `order_id = FXBRIEF-{userId}-{timestampMillis}`.
  The response returns `snapToken` and `clientKey`; the frontend opens the Snap popup with
  these (no redirect). `orderId` is returned for client-side reference.
- No subscription state is mutated and no `subscription_audit_logs` row is written here —
  those happen on confirmed payment via the webhook.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "targetPlan": {
      "id": 3,
      "name": "PREMIUM",
      "price": 20.00,
      "reportQuota": 20
    },
    "orderId": "FXBRIEF-42-1747468800000",
    "snapToken": "66e4fa55-fdac-4ef9-91b5-733b97d1b862",
    "clientKey": "SB-Mid-client-xxxxxxxxxxxxxxxx",
    "warningFlag": true,
    "exchangeRate": 16250.000000,
    "amountIdr": 325000,
    "carryOverCalculation": {
      "remainingReports": 7,
      "additionalReports": 20,
      "newTotal": 27
    }
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `400 VALIDATION_FAILED` — request validation failed (e.g. missing `plan`).
- `400 INVALID_PLAN_FOR_TOP_UP` — `plan` is not `BASIC` or `PREMIUM`.
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 PAYMENT_NOT_AVAILABLE` — sandbox phase and the user is not whitelisted for payment.
- `404 USER_NOT_FOUND` — the authenticated user record could not be loaded.
- `404 NOT_FOUND` — no subscription exists for the authenticated user (see notes on
  `GET /subscription`).
- `503 EXCHANGE_RATE_UNAVAILABLE` — no USD/IDR rate is currently cached (cold start before
  the first successful fetch).
- `503 PAYMENT_INITIATION_FAILED` — the Midtrans Snap transaction could not be created.

### `GET /api/exchange-rate`

Returns the current USD/IDR rate the backend uses to price top-ups, for display on the
top-up screen.

**Authentication:** required (Bearer JWT)

**Behaviour:**
- Returns the single cached rate row. The rate is refreshed daily at 22:45 UTC and on
  startup when the cached value is older than 24 hours; a failed refresh leaves the last
  known rate in place.
- `usdToIdr` is the rate; `fetchedAt` is when it was last successfully fetched from the
  upstream source.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "usdToIdr": 16250.000000,
    "fetchedAt": "2026-05-17T22:45:01Z"
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `503 EXCHANGE_RATE_UNAVAILABLE` — no rate has been cached yet (cold start before the
  first successful fetch).

### `POST /payment/webhook`

Midtrans HTTP notification (server-to-server callback). Not called by the frontend.

**Authentication:** none. This endpoint is unauthenticated by design and is excluded from
the JWT filter; authenticity is established by verifying the Midtrans signature on the
payload, not by a bearer token.

**Behaviour:**
- The raw JSON body is read as-is. The signature is verified as
  `SHA-512(order_id + status_code + gross_amount + server_key)` compared (constant-time)
  against the payload's `signature_key`. A mismatch is rejected with `401` and no state is
  changed.
- The raw payload is recorded in `payment_callbacks` for audit.
- The transaction is located by `order_id`. An unknown order id is acknowledged with `200`
  and ignored. A transaction already marked paid is acknowledged with `200` as a duplicate
  and not re-credited (idempotent).
- On the first successful Midtrans status (`capture` or `settlement`, with `fraud_status`
  of `accept` or absent): the paid plan is assigned, 20 reports are added with carry-over
  (`remaining + 20`), `has_ever_paid` is set, the new plan is propagated to today's
  unarchived report row if one exists, and a `TOP_UP` row is written to
  `subscription_audit_logs` with `performed_by` equal to the paying user. The transaction
  is marked `PAID`. The credited plan and count come from the stored transaction, not from
  the webhook payload.
- Terminal unsuccessful statuses (`deny`, `cancel`, `expire`, `failure`) mark the
  transaction `FAILED`. Other statuses are acknowledged with no state change.

**Success response (200):**

```json
{
  "success": true,
  "data": null,
  "timestamp": "2026-05-17T08:05:00Z"
}
```

**Errors:**
- `400 MALFORMED_REQUEST` — the body is empty or not valid JSON.
- `401 INVALID_PAYMENT_SIGNATURE` — the signature did not match.

### `POST /reports/generate`

Generates the authenticated user's report for the current forex market day. The
response shape is narrowed at the backend by the row's frozen
`planAtGeneration` so each plan receives only the data its UI renders
(D-055). The frontend branches on `planAtGeneration` to know whether to read
`payload.pairs[]` (Premium) or `payload.bestPairView` + `payload.compactPreviews`
(Free/Basic).

**Authentication:** required (Bearer JWT)

**Request body:** optional. When present, supports a one-time preference
override (D-061):

```json
{
  "preferenceType": "TRADING_STYLE",
  "preferenceValue": "SWING_TRADER"
}
```

Both `preferenceType` and `preferenceValue` must be provided together. When
one is omitted or blank, the endpoint returns `400 VALIDATION_FAILED`.
When the body is absent entirely (or both fields are null), the user's
persisted `user_preferences` row is used; if no row exists, no
compatibility scoring is applied.

Valid `preferenceType` values: `TRADING_STYLE`, `PREFERRED_SESSION`,
`RISK_PROFILE`, `FAVORITE_PAIR`. Valid `preferenceValue` values per type:

| `preferenceType`     | Valid `preferenceValue`                                    |
|----------------------|------------------------------------------------------------|
| `TRADING_STYLE`      | `SCALPER`, `INTRADAY`, `SWING_TRADER`, `POSITION_TRADER`  |
| `PREFERRED_SESSION`  | `ASIAN`, `LONDON`, `NEW_YORK`                              |
| `RISK_PROFILE`       | `CONSERVATIVE`, `BALANCED`, `AGGRESSIVE`                   |
| `FAVORITE_PAIR`      | any supported pair symbol (e.g. `EUR/USD`)                |

An override does **not** update the user's persisted preference — it
applies for this generation only. The override is what lands in the
resulting row's `preferenceSnapshot`.

**Behaviour:**

The request is processed in three phases.

1. **Pre-check (read-only).** The subscription is loaded and verified. The endpoint
   short-circuits with the corresponding error code on any of the following:
   - the authenticated user account is not active → `403 ACCOUNT_INACTIVE`;
   - the forex market is currently closed (Friday 22:00 UTC → Sunday 22:00 UTC) →
     `409 MARKET_CLOSED`;
   - a `user_reports` row already exists for the user on the current forex market
     date → `409 DAILY_LIMIT_REACHED`;
   - the subscription's `remaining_reports` is zero or negative →
     `409 NO_REMAINING_REPORTS`.
   The pre-check also resolves the active preference (override → persisted →
   none) and validates it; an invalid override returns `400 VALIDATION_FAILED`
   without consuming a report credit.
2. **Analysis.** The analysis engine resolves the current `fetch_id`. If a
   `market_analysis` row already exists for that id, its stored payload is returned
   without any Claude API call. Otherwise the full SMC pipeline runs, narratives
   are generated via the mega-call (with per-pair fallback per DECISIONS D-045), a
   short one-line summary is composed from the payload, and the row is persisted.
   See PRD §11.3 and DECISIONS D-044 for the shared-analysis contract.
   When a preference is active, `PreferenceScorer` computes the per-pair
   `finalDisplayScore` map from the deserialised payload, `PayloadReorderer`
   produces a new `ReportPayload` whose `pairs` list is best-first by score and
   whose `bestPair` is the top-scored pair. The shared `market_analysis.payload`
   JSONB is never modified — reordering is a per-request projection only (D-061).
3. **Commit (advisory-locked transaction).** A Postgres transaction-scoped advisory
   lock keyed on `user_id` serialises concurrent double-taps from the same user.
   The pre-check invariants are re-verified under the lock. If the payload's
   `marketsConsolidating` flag is `false`, `remaining_reports` is decremented and a
   `subscription_usage` row is inserted; if `true`, neither happens (zero-content
   rule, PRD §5.5). A `user_reports` row is inserted referencing the `market_analysis`
   row with `summary` copied verbatim and `plan_at_generation` set to the
   subscription's current base plan id.
4. **Response narrowing.** The deserialised payload is run through
   `ReportPayloadNarrower.narrowForLive` keyed on the new row's
   `planAtGeneration`. Free/Basic responses receive a `NarrowedReportPayload`
   carrying only `bestPairView` + three `compactPreviews`; Premium responses
   receive the full `ReportPayload` with `layer`, internal engine indices, and
   low-importance economic events trimmed. The stored
   `market_analysis.payload` JSONB always contains the full Premium-depth
   content; narrowing is purely a read-time projection (D-055).

The response includes a `reportsExhausted` flag that is `true` when this generation
caused `remaining_reports` to reach zero. When it is `true`, a reports-exhausted email
is sent to the user via Resend (Phase 5A) prompting them to top up. The commit has
already completed at that point, so the send is dispatched immediately on the background
email executor; a send failure is logged and does not affect the generation response
(email is non-critical).

The shared-analysis layer makes generation cheap for every user beyond the first in
each fetch cycle: subsequent callers in the same cycle reuse the existing
`market_analysis` row with no Claude call.

**Success response (200) — Premium:**

```json
{
  "success": true,
  "data": {
    "reportId": 17,
    "summary": "3 setups available — GBP/USD best opportunity",
    "payload": {
      "bestPair": "GBP/USD",
      "pairs": [
        { "pair": "GBP/USD", "signalState": "CONFIRMED",
          "setupStatus": "Confirmed long near H4 demand — high conviction",
          "shortReasoning": "H4 bullish BOS with M15 follow-through. Fresh zone with Fibonacci confluence supports the entry.",
          "executiveReasoning": "...",
          "invalidationNote": "Bullish bias invalidates if H4 closes below 1.27500.",
          "fundamentalSummary": "...",
          "structureByTimeframe": { "...": "..." },
          "activeZone": { "...": "..." },
          "confidence": { "score": 11, "level": "HIGH", "factors": [ "..." ] },
          "m15Confirmation": { "...": "..." },
          "tradePlan": { "direction": "LONG", "entryLow": 1.27500, "entryHigh": 1.27650, "takeProfit": 1.28400, "stopLoss": 1.27300 },
          "fundamental": { "currency": "GBP", "bias": "BULLISH", "highImpactThisWeek": true, "events": [ "Medium/High importance only" ] },
          "htfConflict": false,
          "fundamentalConflict": false,
          "layer": null }
      ],
      "marketsConsolidating": false,
      "generatedAt": "2026-05-17T08:00:00Z",
      "marketDataFetchedAt": "2026-05-17T07:55:00Z",
      "calendarFetchedAt": "2026-05-17T06:30:00Z"
    },
    "forexMarketDate": "2026-05-18",
    "generatedAt": "2026-05-17T08:00:00Z",
    "planAtGeneration": "PREMIUM",
    "countedAgainstLimit": true,
    "remainingReports": 19,
    "reportsExhausted": false,
    "preferenceSnapshot": {
      "preferenceType": "TRADING_STYLE",
      "preferenceValue": "SWING_TRADER"
    },
    "finalDisplayScores": {
      "GBP/USD": 8.4,
      "EUR/USD": 6.6,
      "USD/JPY": 5.2,
      "AUD/USD": 4.0,
      "USD/CHF": 3.8,
      "USD/CAD": 2.8,
      "NZD/USD": 1.4,
      "XAU/USD": 0.6
    },
    "preferenceMatches": {
      "GBP/USD": true,
      "EUR/USD": true,
      "USD/JPY": false,
      "AUD/USD": false,
      "USD/CHF": false,
      "USD/CAD": false,
      "NZD/USD": false,
      "XAU/USD": false
    }
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

`preferenceSnapshot`, `finalDisplayScores`, and `preferenceMatches` are
present only when the generation applied a preference (override or
persisted). When no preference applied, all three are absent from the
wire (per `@JsonInclude(NON_NULL)`). When `preferenceSnapshot` is
present, the `payload.pairs` list (Premium) and `payload.bestPair` /
`bestPairView` (Free/Basic) reflect the score-based reordering — see
D-061. The `preferenceMatches` map flags each pair as `true` when its
underlying `userCompatibilityScore` meets the match threshold (currently
0.7), letting the frontend render a "matches your preference" badge
without re-deriving the score itself (D-062).

Each pair entry in Premium `payload.pairs` carries the same structure as the
Phase 3A `PairAnalysis` record (multi-timeframe structure map, active zone,
confidence breakdown, M15 confirmation flags, signal lifecycle state, trade
plan, fundamental assessment, conflict flags, and the five Claude-generated
text fields per PRD §8.4) with three audit-driven trims applied:
`layer` is `null`, internal engine indices (`formedBarIndex`, swing
`barIndex`) are `0`, and `fundamental.events` is filtered to Medium and
High importance only.

**Success response (200) — Free or Basic:**

```json
{
  "success": true,
  "data": {
    "reportId": 17,
    "summary": "3 setups available — GBP/USD best opportunity",
    "payload": {
      "bestPair": "GBP/USD",
      "bestPairView": {
        "pair": "GBP/USD",
        "dailyBias": "BULLISH",
        "confidenceLevel": "HIGH",
        "majorNewsRisk": true,
        "signalState": "CONFIRMED",
        "setupStatus": "Confirmed long near H4 demand — high conviction",
        "shortReasoning": "H4 bullish BOS with M15 follow-through. Fresh zone with Fibonacci confluence supports the entry.",
        "tradePlan": { "direction": "LONG", "entryLow": 1.27500, "entryHigh": 1.27650, "takeProfit": 1.28400, "stopLoss": 1.27300 }
      },
      "compactPreviews": [
        { "pair": "EUR/USD", "dailyBias": "BEARISH", "signalState": "AWAITING_CONFIRMATION" },
        { "pair": "USD/JPY", "dailyBias": "RANGING", "signalState": "DETECTED" },
        { "pair": "AUD/USD", "dailyBias": "BULLISH", "signalState": "EXPIRED" }
      ],
      "marketsConsolidating": false,
      "generatedAt": "2026-05-17T08:00:00Z",
      "marketDataFetchedAt": "2026-05-17T07:55:00Z"
    },
    "forexMarketDate": "2026-05-18",
    "generatedAt": "2026-05-17T08:00:00Z",
    "planAtGeneration": "BASIC",
    "countedAgainstLimit": true,
    "remainingReports": 4,
    "reportsExhausted": false,
    "preferenceSnapshot": {
      "preferenceType": "TRADING_STYLE",
      "preferenceValue": "SWING_TRADER"
    },
    "finalDisplayScores": {
      "GBP/USD": 8.4,
      "EUR/USD": 6.6,
      "USD/JPY": 5.2,
      "AUD/USD": 4.0,
      "USD/CHF": 3.8,
      "USD/CAD": 2.8,
      "NZD/USD": 1.4,
      "XAU/USD": 0.6
    },
    "preferenceMatches": {
      "GBP/USD": true,
      "EUR/USD": true,
      "USD/JPY": false,
      "AUD/USD": false,
      "USD/CHF": false,
      "USD/CAD": false,
      "NZD/USD": false,
      "XAU/USD": false
    }
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

`bestPairView` carries the eight fields PRD §8.2 specifies for both the
Summary Section (`pair`, `dailyBias`, `confidenceLevel`, `majorNewsRisk`,
`signalState`) and the Best Pair Card (`pair`, `dailyBias`,
`confidenceLevel`, `setupStatus`, `shortReasoning`, `tradePlan`, plus
`signalState` for the locked-button state). `compactPreviews` is exactly
three entries — highest, middle, lowest confidence score among non-best
pairs — driving the dashboard's "Additional Market Coverage — Unlock
Premium" upsell. No other per-pair fields are on the wire.
`calendarFetchedAt` is also absent from the narrowed payload (Free/Basic
do not render economic events).

The "markets consolidating" case for Free/Basic carries
`bestPairView: null` and `compactPreviews: null` (both omitted via
`@JsonInclude(NON_NULL)`); the frontend renders this state from
`marketsConsolidating: true` alone.

**Errors:**
- `400 VALIDATION_FAILED` — invalid preference override (partial fields, unknown
  type or value, or pair symbol not in the supported set).
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 ACCOUNT_INACTIVE` — authenticated user is not active.
- `404 NOT_FOUND` — no subscription exists for the authenticated user.
- `409 MARKET_CLOSED` — forex market is currently closed.
- `409 DAILY_LIMIT_REACHED` — report already generated for the current forex day.
- `409 NO_REMAINING_REPORTS` — zero remaining reports.
- `503 MARKET_DATA_NOT_READY` — pre-fetch has not yet produced a complete cycle.
   The client should retry after a short delay; no report credit is consumed.
- `503 MARKET_DATA_UNAVAILABLE` — upstream OHLCV provider failed after retries.
- `503 NARRATIVE_UNAVAILABLE` — Claude API failed after retries. The structured
   analysis was computed but the per-pair text fields could not be generated.

### `GET /reports/today`

Returns the authenticated user's report for the current forex market day if one
exists. Does not trigger generation.

**Authentication:** required (Bearer JWT)

**Request body:** none

**Behaviour:**
- The `user_reports` row keyed by `(user_id, forex_market_date)` is loaded where
  `forex_market_date = ForexMarketClock.currentForexMarketDate()`.
- If no row exists, the endpoint returns `200 OK` with `data` omitted from the
  response (per the envelope's `@JsonInclude(NON_NULL)` rule). The frontend treats
  the absence of `data` as "not yet generated today".
- If a row exists, the linked `market_analysis.payload` JSONB is deserialised and
  run through `ReportPayloadNarrower.narrowForLive` keyed on the row's frozen
  `planAtGeneration` (D-055). The response shape matches `POST /reports/generate`
  exactly: Premium gets the full payload with trims; Free/Basic get
  `bestPairView` + three `compactPreviews`. The current `remaining_reports` value
  from the subscription is included for dashboard convenience — it reflects the
  live counter, not a frozen value.
- When the row carries a `preference_snapshot`, the deserialised payload is first
  reordered by the row's stored `final_display_scores` so the same pair ordering
  the user saw at generation time is reproduced exactly, regardless of any
  subsequent change to the user's persisted preference (D-061). Both
  `preferenceSnapshot` and `finalDisplayScores` are returned on the response.
  `preferenceMatches` is recomputed per read from the stored snapshot against
  the deserialised payload — a future change to the match threshold applies
  uniformly to all rows (D-062).
- `reportsExhausted` is always `false` on this endpoint; the flag's meaning is
  "this call caused remaining to hit zero", which is generation-only.

**Success response (200) — report exists:**

Same shape as `POST /reports/generate` — Premium gets the full `pairs[]` array
with audit trims; Free/Basic get `bestPairView` and three `compactPreviews`.

**Success response (200) — no report yet today:**

```json
{
  "success": true,
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `404 NOT_FOUND` — no subscription exists for the authenticated user.
- `500 INTERNAL_ERROR` — the stored payload could not be deserialised. This is a
   defensive branch only; the stored format is fixed by the Phase 3A
   `ReportPayload` record.

### `GET /reports/history`

Returns the authenticated user's archived report history. List view only — each
row carries the date, the plan badge frozen at generation time, and the short
summary string. The full payload is fetched on demand via
`GET /reports/history/{reportId}`.

Access is gated by the **effective plan** (PRD §5.4). A user whose base plan
is Basic or Premium but whose remaining reports lapsed across a forex day
boundary sees the same locked response a Free user does; topping up restores
visibility immediately.

**Authentication:** required (Bearer JWT)

**Query parameters:**
- `page` — optional, integer, 1-indexed. Defaults to `1`. Only consulted for
  Premium effective plan; ignored otherwise. Values below `1` are clamped to
  `1`. Values past the last page return `items: []` with the true
  `totalPages` so the frontend can correct its state.

**Behaviour:**
- Effective plan `FREE` (Free users and lapsed Basic/Premium users) — returns
  `locked: true` with an empty `items` list and zeroed counters. The
  frontend renders the blurred-table upsell. No row counts are disclosed.
- Effective plan `BASIC` — returns at most the latest ten archived rows in
  `items`, ordered newest forex market day first. `totalArchivedCount` is the
  true total of archived reports for this user; the frontend uses it to
  decide whether to render the "X older reports available in Premium"
  message (shown only when `totalArchivedCount > 10`, per PRD §9.2). `page`
  is always `1` and `totalPages` is `0` when there are no items, otherwise
  `1` — the Basic view does not paginate.
- Effective plan `PREMIUM` — returns the requested page of archived rows,
  ten per page, ordered newest forex market day first. `totalArchivedCount`
  is the total across all pages. `totalPages` reflects the full archive
  size.
- Today's not-yet-archived report is never included; the dashboard exposes
  it via `GET /reports/today`. Archive flip happens at the daily 22:00 UTC
  scheduler.
- History rows are immutable. `planAtGeneration` is the plan code frozen at
  write time — never re-evaluated against the user's current plan.

**Success response (200) — Premium active, page 2 of 4 total pages:**

```json
{
  "success": true,
  "data": {
    "locked": false,
    "items": [
      {
        "reportId": 314,
        "forexMarketDate": "2026-05-17",
        "planAtGeneration": "PREMIUM",
        "summary": "3 setups available — GBP/USD best opportunity",
        "preferenceSnapshot": {
          "preferenceType": "TRADING_STYLE",
          "preferenceValue": "SWING_TRADER"
        }
      },
      {
        "reportId": 305,
        "forexMarketDate": "2026-05-16",
        "planAtGeneration": "PREMIUM",
        "summary": "Markets consolidating — no clear setups"
      }
    ],
    "totalArchivedCount": 37,
    "page": 2,
    "pageSize": 10,
    "totalPages": 4
  },
  "timestamp": "2026-05-18T08:00:00Z"
}
```

Each item carries `preferenceSnapshot` only when a preference was active
at generation time — rows generated without a preference omit the field
entirely (per `@JsonInclude(NON_NULL)`). The snapshot is frozen at write
time and never changes; a user who later changes their persisted
preference still sees the original value in history (D-061).

**Success response (200) — Free or lapsed:**

```json
{
  "success": true,
  "data": {
    "locked": true,
    "items": [],
    "totalArchivedCount": 0,
    "page": 0,
    "pageSize": 0,
    "totalPages": 0
  },
  "timestamp": "2026-05-18T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `404 NOT_FOUND` — no subscription exists for the authenticated user.

### `GET /reports/history/{reportId}`

Returns an archived report identified by `reportId`. The response shape
mirrors `POST /reports/generate` and `GET /reports/today` for the same
plan, with one difference for Free/Basic: the `compactPreviews` array is
omitted (no upsell affordance is exposed inside archived content per the
"clean reading experience" rule — D-055).

**Authentication:** required (Bearer JWT)

**Path parameters:**
- `reportId` — numeric report identifier from a previous history list entry.

**Behaviour:**
- Access is gated by effective plan, identically to `GET /reports/history`.
  A user whose effective plan is `FREE` receives `404 NOT_FOUND` for any
  `reportId` — no row counts or ids are disclosed to a locked viewer.
- The report row must (a) exist, (b) belong to the authenticated user, and
  (c) be archived. Otherwise `404 NOT_FOUND` is returned. The three
  conditions are collapsed into a single non-disclosive `NOT_FOUND` so a
  caller cannot probe ownership by id.
- Content visibility follows the row's `planAtGeneration`, **not** the
  caller's current plan. A report generated at Basic depth is returned at
  Basic depth forever; a report generated at Premium depth is returned at
  Premium depth forever. This preserves the immutability rule (PRD §8.1):
  upgrading or downgrading after generation does not retroactively change
  what a historical report contained.
- The deserialised payload is run through
  `ReportPayloadNarrower.narrowForArchived` keyed on the row's frozen
  `planAtGeneration` (D-055):
  - **Premium rows** receive the full `ReportPayload` with `layer`,
    internal engine indices, and low-importance economic events trimmed
    — identical to the Premium response on `POST /reports/generate`.
  - **Basic and Free rows** receive a `NarrowedReportPayload` carrying
    `bestPairView` only. `compactPreviews` is null and omitted via
    `@JsonInclude(NON_NULL)`; no upsell affordance appears in archived
    content. If `bestPair` is null or no matching pair entry is found
    (consolidating-markets case), both `bestPairView` and
    `compactPreviews` are absent and the frontend renders from
    `marketsConsolidating: true` alone.
- When the row carries a `preference_snapshot`, the deserialised payload
  is first reordered by the row's stored `final_display_scores` so the
  order and `bestPair` reflect the preference active at generation time,
  never the user's current preference (D-061). Both `preferenceSnapshot`
  and `finalDisplayScores` are returned on the response.
  `preferenceMatches` is recomputed per read from the stored snapshot
  against the deserialised payload (D-062) — same logic as the live
  read path, so a threshold change applies uniformly.
- Narrowing is a pure read-time projection; the stored
  `market_analysis.payload` always contains the full Premium-depth content
  (engine always produces Premium-depth output, D-042) and is never
  mutated.
- `remainingReports` reflects the current live subscription counter (same
  semantics as `GET /reports/today`), not a value frozen at archive time.
- `reportsExhausted` is always `false` on this endpoint — its meaning
  ("this call drove remaining to zero") applies only to generation.

**Success response (200) — archived report whose `planAtGeneration` is `BASIC`:**

```json
{
  "success": true,
  "data": {
    "reportId": 314,
    "summary": "3 setups available — GBP/USD best opportunity",
    "payload": {
      "bestPair": "GBP/USD",
      "bestPairView": {
        "pair": "GBP/USD",
        "dailyBias": "BULLISH",
        "confidenceLevel": "HIGH",
        "majorNewsRisk": true,
        "signalState": "CONFIRMED",
        "setupStatus": "Confirmed long near H4 demand — high conviction",
        "shortReasoning": "H4 bullish BOS with M15 follow-through. Fresh zone with Fibonacci confluence supports the entry.",
        "tradePlan": { "direction": "LONG", "entryLow": 1.27500, "entryHigh": 1.27650, "takeProfit": 1.28400, "stopLoss": 1.27300 }
      },
      "marketsConsolidating": false,
      "generatedAt": "2026-05-17T08:00:00Z",
      "marketDataFetchedAt": "2026-05-17T07:55:00Z"
    },
    "forexMarketDate": "2026-05-17",
    "generatedAt": "2026-05-17T08:00:00Z",
    "planAtGeneration": "BASIC",
    "countedAgainstLimit": true,
    "remainingReports": 12,
    "reportsExhausted": false,
    "preferenceSnapshot": {
      "preferenceType": "TRADING_STYLE",
      "preferenceValue": "SWING_TRADER"
    },
    "finalDisplayScores": {
      "GBP/USD": 8.4,
      "EUR/USD": 6.6,
      "USD/JPY": 5.2,
      "AUD/USD": 4.0,
      "USD/CHF": 3.8,
      "USD/CAD": 2.8,
      "NZD/USD": 1.4,
      "XAU/USD": 0.6
    },
    "preferenceMatches": {
      "GBP/USD": true,
      "EUR/USD": true,
      "USD/JPY": false,
      "AUD/USD": false,
      "USD/CHF": false,
      "USD/CAD": false,
      "NZD/USD": false,
      "XAU/USD": false
    }
  },
  "timestamp": "2026-05-18T08:00:00Z"
}
```

Note: no `compactPreviews` field in the response. A Basic-generated report
on the live dashboard (`GET /reports/today`) does carry that field; the
archived view does not.

A report whose `planAtGeneration` is `PREMIUM` returns the full payload —
all eight pair entries in `payload.pairs` with the audit trims applied —
regardless of the caller's current plan.

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `404 NOT_FOUND` — report does not exist, does not belong to the caller,
  is not archived, the caller's effective plan is `FREE`, or the caller has
  no subscription. The response does not disclose which.
- `500 INTERNAL_ERROR` — the stored payload could not be deserialised.
  Defensive branch only.

### `GET /user/preferences`

Returns the authenticated user's currently-saved market preference, or an
empty marker when none is set. Backs the Account page picker's initial state.

**Authentication:** required (Bearer JWT)

**Request body:** none

**Behaviour:**
- Reads the at-most-one `user_preferences` row for the user (V10 enforces
  `UNIQUE (user_id)`).
- When a row exists, `set` is `true` and `preferenceType` / `preferenceValue`
  carry it. When no row exists, `set` is `false` and both fields are omitted.
- This is the saved-default read. The dashboard one-time override is a
  separate, session-only concept passed in the `POST /reports/generate` body
  and is never persisted here (D-061).

**Success response (200) — preference set:**

```json
{
  "success": true,
  "data": {
    "set": true,
    "preferenceType": "TRADING_STYLE",
    "preferenceValue": "SWING_TRADER"
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Success response (200) — no preference:**

```json
{
  "success": true,
  "data": { "set": false },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.

### `GET /user/preferences/options`

Returns the static picklist for the two-step preference selector: the four
preference types and the allowed values per type. The response is identical
for every user and carries no per-user state — the user's current selection
comes from `GET /user/preferences`.

**Authentication:** required (Bearer JWT)

**Request body:** none

**Behaviour:**
- `TRADING_STYLE`, `PREFERRED_SESSION`, and `RISK_PROFILE` values come from the
  `PreferenceValue` enum; `FAVORITE_PAIR` values are the supported pair symbols
  sourced from `analysis.entity.Pair` (the single source of truth for symbols,
  D-061).

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "types": [
      { "preferenceType": "TRADING_STYLE", "values": ["SCALPER", "INTRADAY", "SWING_TRADER", "POSITION_TRADER"] },
      { "preferenceType": "PREFERRED_SESSION", "values": ["ASIAN", "LONDON", "NEW_YORK"] },
      { "preferenceType": "RISK_PROFILE", "values": ["CONSERVATIVE", "BALANCED", "AGGRESSIVE"] },
      { "preferenceType": "FAVORITE_PAIR", "values": ["EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD", "USD/CAD", "NZD/USD", "XAU/USD"] }
    ]
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.

### `PUT /user/preferences`

Sets or replaces the authenticated user's saved preference. Upserts the single
`user_preferences` row.

**Authentication:** required (Bearer JWT)

**Request body:** required.

```json
{
  "preferenceType": "RISK_PROFILE",
  "preferenceValue": "CONSERVATIVE"
}
```

**Validation rules:**
- Both fields are required and non-blank, else `400 VALIDATION_FAILED`.
- `preferenceType` must be one of `TRADING_STYLE`, `PREFERRED_SESSION`,
  `RISK_PROFILE`, `FAVORITE_PAIR` (case-insensitive, trimmed).
- `preferenceValue` must be valid for the chosen type:

| `preferenceType`     | Valid `preferenceValue`                                   |
|----------------------|-----------------------------------------------------------|
| `TRADING_STYLE`      | `SCALPER`, `INTRADAY`, `SWING_TRADER`, `POSITION_TRADER`   |
| `PREFERRED_SESSION`  | `ASIAN`, `LONDON`, `NEW_YORK`                             |
| `RISK_PROFILE`       | `CONSERVATIVE`, `BALANCED`, `AGGRESSIVE`                   |
| `FAVORITE_PAIR`      | any supported pair symbol (e.g. `EUR/USD`)                |

  Non-pair values are matched case-insensitively against the enum; pair symbols
  are validated via `Pair.fromSymbol`. A value not valid for the type returns
  `400 VALIDATION_FAILED`.

**Behaviour:**
- When a row exists it is replaced in place (type + value), else a new row is
  inserted. `updated_at` is stamped on write.
- Returns the saved preference in the same shape as `GET /user/preferences`.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "set": true,
    "preferenceType": "RISK_PROFILE",
    "preferenceValue": "CONSERVATIVE"
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `400 VALIDATION_FAILED` — missing field, unknown type, or value invalid for
  the type.
- `401 UNAUTHENTICATED` — missing or invalid JWT.

### `DELETE /user/preferences`

Removes the authenticated user's saved preference (reset to no preference).

**Authentication:** required (Bearer JWT)

**Request body:** none

**Behaviour:**
- Deletes the user's `user_preferences` row if present. Idempotent — when no
  row exists the call still succeeds (the end state, "no preference", is the
  same either way).
- After this call the report flow applies default market-based ranking with no
  compatibility scoring until a new preference is set.

**Success response (200):**

```json
{
  "success": true,
  "data": null,
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.

### `POST /feedback`

Submits feedback from the authenticated user (Account page Feedback modal).

**Authentication:** required (Bearer JWT)

**Request body:** required.

```json
{
  "content": "The weekend lock message could show my local time too."
}
```

**Validation rules:**
- `content` is required, non-blank, max 5000 characters, else
  `400 VALIDATION_FAILED`.

**Behaviour:**
- Persists a `feedback` row with `status = PENDING` and the current timestamp.
- After the transaction commits, two emails are sent via Resend (Phase 5A): a
  thank-you email to the submitting user, and a notification email carrying the
  full feedback content to `ADMIN_NOTIFICATION_EMAIL`. Both are background and
  non-critical — a send failure is logged to `email_logs` and never affects the
  submission response.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "id": 91,
    "createdAt": "2026-05-17T08:00:00Z"
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `400 VALIDATION_FAILED` — blank or oversized content.
- `401 UNAUTHENTICATED` — missing or invalid JWT.

## Admin endpoints

All endpoints under `/admin` require an `ADMIN` role on the JWT. Non-admin
authenticated callers receive `403 FORBIDDEN`; unauthenticated callers receive
`401 UNAUTHENTICATED`. The role check is enforced at two layers — the URL
matcher in `SecurityConfig` (`requestMatchers("/admin/**").hasRole("ADMIN")`)
and `@PreAuthorize("hasRole('ADMIN')")` on the controller class — for
defence in depth.

Admin actions targeting another admin user (manual top-up,
activate/deactivate) return `403 CANNOT_MODIFY_ADMIN`. Admin users do not
appear in the admin user list endpoint either, so the admin UI cannot
accidentally surface them as actionable rows.

Every successful mutation writes a row to `subscription_audit_logs` with the
acting admin id in `performed_by` and one of the action values documented in
D-034: `ADMIN_TOP_UP`, `ADMIN_PLAN_CHANGE`, `ADMIN_ACTIVATION`,
`ADMIN_DEACTIVATION`. Activate/deactivate calls that are no-ops (target
already in the requested state) do not write an audit row — the audit trail
records state transitions, not button presses.

### `GET /admin/dashboard/stats`

Returns the quick-stats counter strip rendered at the top of the Admin
Dashboard page. The four analytics chart endpoints below complete the page;
this endpoint covers the four static numbers PRD §9.3 specifies.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Request body:** none

**Behaviour:**
- `totalActiveSubscribers` — count of `subscriptions` rows whose plan is
  `BASIC` or `PREMIUM` and whose user has `is_active = true`.
- `totalReportsCurrentForexMonth` — count of `user_reports` rows whose
  `forex_market_date` falls within the calendar month of the current forex
  market date (1st through last day inclusive, UTC, computed against
  `ForexMarketClock#currentForexMarketDate()`).
- `totalFreeUsersActive` — count of `subscriptions` rows on the `FREE` plan
  whose user has `is_active = true`.
- `usersAtZeroRemainingReports` — count of `subscriptions` rows where
  `remaining_reports = 0`, irrespective of plan. Surfaces every user the
  admin might want to nudge toward a top-up.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "totalActiveSubscribers": 14,
    "totalReportsCurrentForexMonth": 217,
    "totalFreeUsersActive": 83,
    "usersAtZeroRemainingReports": 6
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller is authenticated but does not hold the `ADMIN` role.

### `GET /admin/dashboard/revenue`

Revenue per forex market month for the dashboard revenue chart. Covers a
trailing 12-forex-month window ending with the current forex month; months
with no revenue are present with a zero value so the chart axis is continuous.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Request body:** none

**Behaviour:**
- Sums every paid top-up event in `subscription_audit_logs` whose `action` is
  one of `TOP_UP`, `ADMIN_TOP_UP`, or `ADMIN_PLAN_CHANGE`, valuing each at the
  price of the plan it credited (`new_plan` → `subscription_plans.price`).
  `TOP_UP` is the Phase 5B Midtrans payment; the two admin actions are the
  manual billing path used while Midtrans onboarding is pending (D-065, P48).
- Each event is bucketed by the forex market month of its `created_at`,
  computed against the same 22:00 UTC forex-day boundary used elsewhere
  (D-030, D-065).
- `month` is `YYYY-MM`. `revenue` is a decimal USD sum. Ordered oldest month
  first.

**Success response (200):**

```json
{
  "success": true,
  "data": [
    { "month": "2026-04", "revenue": 120.00 },
    { "month": "2026-05", "revenue": 260.00 }
  ],
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller is authenticated but does not hold the `ADMIN` role.

### `GET /admin/dashboard/new-subscribers`

New (first-time) subscribers per forex market month for the dashboard chart.
Same trailing 12-forex-month window and zero-fill as the revenue endpoint.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Request body:** none

**Behaviour:**
- A user counts once, in the forex market month of their **earliest** paid
  top-up event (`MIN(created_at)` over the same paid-action set as the revenue
  endpoint, grouped by user). This is the only dated first-payment signal —
  `has_ever_paid` is an undated boolean (D-065).
- `month` is `YYYY-MM`; `count` is the number of first-time subscribers in that
  month. Ordered oldest month first.

**Success response (200):**

```json
{
  "success": true,
  "data": [
    { "month": "2026-04", "count": 6 },
    { "month": "2026-05", "count": 11 }
  ],
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller is authenticated but does not hold the `ADMIN` role.

### `GET /admin/dashboard/active-inactive`

Current active-vs-inactive split of non-admin users for the dashboard chart.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Request body:** none

**Behaviour:**
- Returns the current counts of non-admin users with `is_active = true` and
  `is_active = false`. This is a point-in-time snapshot, not a time series:
  the schema records only the present `is_active` value with no historical
  snapshots, so a true series cannot be reconstructed from existing data
  (D-065). Admin users are excluded, consistent with the user list.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "active": 478,
    "inactive": 23
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller is authenticated but does not hold the `ADMIN` role.

### `GET /admin/dashboard/report-volume`

Daily report-generation volume for the dashboard chart. Covers a trailing
30-forex-day window ending with the current forex market date; days with no
reports are present with a zero count so the chart axis is continuous.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Request body:** none

**Behaviour:**
- Counts `user_reports` rows grouped by `forex_market_date` over the window.
  Every report counts, including zero-content "markets consolidating" rows
  (those still record a generation event; `counted_against_limit` is the
  separate credit-consumption flag, surfaced on the usage endpoint, not here).
- `forexMarketDate` is an ISO date; `count` is that day's report count.
  Ordered oldest day first.

**Success response (200):**

```json
{
  "success": true,
  "data": [
    { "forexMarketDate": "2026-05-16", "count": 0 },
    { "forexMarketDate": "2026-05-17", "count": 31 }
  ],
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller is authenticated but does not hold the `ADMIN` role.

### `GET /admin/users`

Returns a paginated list of non-admin users for the Admin Users page. Admin
users are excluded from the result set so the panel cannot be used to lock
another admin out.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Query parameters:**
- `page` — optional, integer, 1-indexed. Defaults to `1`. Values below `1`
  are clamped to `1`. Values past the last page return `items: []` with the
  true `totalPages` so the frontend can correct its state.

**Behaviour:**
- Page size is fixed at 20 (PRD §9.3).
- Results are ordered by user id ascending — a stable order independent of
  any user-mutable field.
- `plan` is the user's base persisted plan code (`FREE`, `BASIC`,
  `PREMIUM`). The admin panel displays the base plan directly; the
  effective-plan lapse projection is a user-side rendering concern and is
  not exposed here.
- `lastGeneratedAt` is the most recent `generated_at` across every
  `user_reports` row for the user (archived or live), or `null` if the user
  has never generated a report. Computed in a single grouped query against
  the page's user ids — no N+1.
- `deletionRequestedAt` is non-null only when the user has an in-flight
  deletion request. The admin UI uses presence to render the "pending
  deletion" indicator. Admins cannot cancel deletion requests; that is a
  user-only action (PRD §9.3).

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 42,
        "email": "trader@example.com",
        "name": "Jane Trader",
        "plan": "PREMIUM",
        "remainingReports": 14,
        "active": true,
        "hasEverPaid": true,
        "deletionRequestedAt": null,
        "lastGeneratedAt": "2026-05-17T08:00:00Z"
      }
    ],
    "totalCount": 421,
    "page": 1,
    "pageSize": 20,
    "totalPages": 22
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller is authenticated but does not hold the `ADMIN` role.

### `POST /admin/users/{userId}/top-up`

Manual top-up performed by an admin. Always tied to a plan selection — there
is no separate upgrade/downgrade action. Adds 20 reports with carry-over
(`remaining + 20 = newTotal`), flips `has_ever_paid` on if not already set,
and propagates the new plan to today's unarchived `user_reports` row if one
exists (so a subsequent `GET /reports/today` call returns the upgraded plan
response without regenerating).

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Path parameters:**
- `userId` — numeric id of the target user.

**Request body:**

```json
{
  "plan": "PREMIUM"
}
```

**Validation:**
- `plan` — required, non-blank, max 16 characters. Must be `BASIC` or
  `PREMIUM` (case-insensitive). `FREE` is not a valid top-up target.

**Behaviour:**
- The target user is looked up by id. A missing id returns
  `404 USER_NOT_FOUND`.
- Admin users cannot be targeted — the action returns
  `403 CANNOT_MODIFY_ADMIN` so admins cannot accidentally tamper with one
  another's subscription state.
- The plan code is validated. `BASIC` and `PREMIUM` are the only accepted
  values; anything else (including `FREE`) returns `400 INVALID_PLAN_FOR_TOP_UP`.
- The user's `subscriptions` row is read; the response captures the
  pre-mutation `oldPlan` and `oldRemaining` for the audit row.
- `subscription.plan` is set to the target plan and `remaining_reports` is
  incremented by 20 (the target plan's `report_count`).
- `users.has_ever_paid` is set to `true` if not already — this is the same
  flag the Phase 5B Midtrans confirmation flow flips; admin top-ups must
  not leave it false.
- If the user has an unarchived `user_reports` row whose
  `forex_market_date` equals the current forex market date, its
  `plan_at_generation` is updated to the new plan id. This makes the
  upgraded plan label visible on the user's current-day report immediately
  on next read; the row is still unarchived so this mutation is permitted.
- One row is written to `subscription_audit_logs`. `action` is
  `ADMIN_PLAN_CHANGE` when `oldPlan != newPlan`, otherwise `ADMIN_TOP_UP`.
  All four old/new fields are populated; `performed_by` is the acting
  admin's user id.
- All four mutations (subscription row, user row, today-report row, audit
  row) commit in a single transaction.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "userId": 42,
    "plan": "PREMIUM",
    "remainingReports": 27,
    "hasEverPaid": true,
    "planChanged": true
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `400 VALIDATION_FAILED` — request validation failed (e.g. missing `plan`).
- `400 INVALID_PLAN_FOR_TOP_UP` — `plan` is not `BASIC` or `PREMIUM`.
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.
- `403 CANNOT_MODIFY_ADMIN` — target user holds the `ADMIN` role.
- `404 USER_NOT_FOUND` — no user exists with the supplied id.
- `404 NOT_FOUND` — the target user exists but has no subscription row. This
  should not occur for a normally-provisioned account; documented for defence
  in depth.

### `POST /admin/users/{userId}/activate`

Sets `users.is_active = true` for the target user. If the account is already
active, the call is a no-op and no audit row is written. State transitions
(inactive → active) write one row to `subscription_audit_logs` with
`action = ADMIN_ACTIVATION`. Plan and remaining values are unchanged and the
audit row carries `old_plan = new_plan` and `old_remaining = new_remaining`
for both fields.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Path parameters:**
- `userId` — numeric id of the target user.

**Request body:** none

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "userId": 42,
    "active": true
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.
- `403 CANNOT_MODIFY_ADMIN` — target user holds the `ADMIN` role.
- `404 USER_NOT_FOUND` — no user exists with the supplied id.

### `POST /admin/users/{userId}/deactivate`

Sets `users.is_active = false` for the target user. If the account is already
inactive, the call is a no-op and no audit row is written. State transitions
(active → inactive) write one row to `subscription_audit_logs` with
`action = ADMIN_DEACTIVATION`. Plan and remaining values are unchanged and
the audit row carries `old_plan = new_plan` and
`old_remaining = new_remaining` for both fields.

Deactivating an account does not invalidate any outstanding JWTs the user
already holds — JWT invalidation is gated by token expiry only (P8). The
user's next call to a route gated by `is_active` will be rejected when the
relevant feature checks the flag. This phase introduces no such gate; the
flag is currently consulted at login time only.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Path parameters:**
- `userId` — numeric id of the target user.

**Request body:** none

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "userId": 42,
    "active": false
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.
- `403 CANNOT_MODIFY_ADMIN` — target user holds the `ADMIN` role.
- `404 USER_NOT_FOUND` — no user exists with the supplied id.

### `GET /admin/usage`

Returns a paginated list of report-generation events for the Admin Usage
Overview page. Each row corresponds to one `user_reports` entry — archived
or live — joined to its owning user for name + email rendering.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Query parameters:**
- `page` — optional, integer, 1-indexed. Defaults to `1`. Values below `1`
  are clamped to `1`.
- `from` — optional, ISO-8601 date (`YYYY-MM-DD`). When set, filters to
  rows whose `forex_market_date >= from`.
- `to` — optional, ISO-8601 date (`YYYY-MM-DD`). When set, filters to
  rows whose `forex_market_date <= to`.
- `userId` — optional numeric id. When set, filters to rows belonging to
  that user.

**Validation:**
- If both `from` and `to` are provided and `from > to`, the call returns
  `400 INVALID_DATE_RANGE`.

**Behaviour:**
- Page size is fixed at 20 (PRD §9.3).
- Results are ordered newest first: descending `forex_market_date`, then
  descending `generated_at`, then descending `id` as a stable tiebreaker.
- `planAtGeneration` is the plan code (`FREE` / `BASIC` / `PREMIUM`) frozen
  on the row at generation time, not the user's current plan.
- `countedAgainstLimit` reflects the row's persisted value — `false` only
  when the report fell under the zero-content "markets consolidating" rule
  (PRD §5.5). Archived and live rows are both included; the report-archive
  flip at 22:00 UTC does not change the value.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "reportId": 314,
        "userId": 42,
        "userEmail": "trader@example.com",
        "userName": "Jane Trader",
        "forexMarketDate": "2026-05-17",
        "planAtGeneration": "PREMIUM",
        "countedAgainstLimit": true,
        "generatedAt": "2026-05-17T08:00:00Z"
      }
    ],
    "totalCount": 1834,
    "page": 1,
    "pageSize": 20,
    "totalPages": 92
  },
  "timestamp": "2026-05-17T08:00:00Z"
}
```

**Errors:**
- `400 INVALID_DATE_RANGE` — `from > to`.
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.

### `GET /admin/feedback`

Paginated list of all user feedback for the Admin Feedback Management page.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Query parameters:**
- `page` — optional, integer, 1-indexed. Defaults to `1`. Values below `1` are
  clamped to `1`. Values past the last page return `items: []` with the true
  `totalPages`.

**Behaviour:**
- Page size is fixed at 20 (PRD §9.3).
- Ordered newest submission first (`created_at DESC`, then `id DESC`).
- `replied` is the at-a-glance flag derived from the row's status
  (`REPLIED` → `true`, `PENDING` → `false`). `repliedAt` and `replyContent`
  are present only on replied rows and omitted otherwise.
- The submitting user's `name` and `email` are joined in a single query
  (no N+1).

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 91,
        "userId": 42,
        "userName": "Jane Trader",
        "userEmail": "trader@example.com",
        "content": "The weekend lock message could show my local time too.",
        "submittedAt": "2026-05-17T08:00:00Z",
        "replied": true,
        "repliedAt": "2026-05-17T09:30:00Z",
        "replyContent": "Thanks — we'll add a local-time hint in a future update."
      },
      {
        "id": 90,
        "userId": 17,
        "userName": "Sam Lee",
        "userEmail": "sam@example.com",
        "content": "Love the consolidating-market message.",
        "submittedAt": "2026-05-16T22:14:00Z",
        "replied": false
      }
    ],
    "totalCount": 134,
    "page": 1,
    "pageSize": 20,
    "totalPages": 7
  },
  "timestamp": "2026-05-17T10:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller is authenticated but does not hold the `ADMIN` role.

### `POST /admin/feedback/{feedbackId}/reply`

Records an admin reply to a feedback submission and marks it replied.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Path parameters:**
- `feedbackId` — the id of the feedback row to reply to.

**Request body:** required.

```json
{
  "replyContent": "Thanks — we'll add a local-time hint in a future update."
}
```

**Validation rules:**
- `replyContent` is required, non-blank, max 5000 characters, else
  `400 VALIDATION_FAILED`.

**Behaviour:**
- Transitions the row from `PENDING` to `REPLIED`, stamps `replied_at`, and
  stores `reply_content`.
- After the transaction commits, the admin's reply is emailed to the user via
  Resend (Phase 5A) with subject `Re: Your feedback on FX–Brief`. The send is
  background and non-critical — a failure is logged to `email_logs` and does not
  affect the reply response.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "id": 91,
    "replied": true,
    "repliedAt": "2026-05-17T09:30:00Z",
    "replyContent": "Thanks — we'll add a local-time hint in a future update."
  },
  "timestamp": "2026-05-17T09:30:00Z"
}
```

**Errors:**
- `400 VALIDATION_FAILED` — blank or oversized reply content.
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller is authenticated but does not hold the `ADMIN` role.
- `404 FEEDBACK_NOT_FOUND` — no feedback with the given id.
- `409 FEEDBACK_ALREADY_REPLIED` — the feedback has already been replied to.

### `GET /admin/weekly-summaries`

Paginated list of weekly market summaries for the admin review page.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Query parameters:**
- `page` — optional, integer, 1-indexed. Defaults to `1`. Values below `1` are
  clamped to `1`.
- `status` — optional. When present, filters to one status; must be one of
  `DRAFT`, `PUBLISHED`, `ARCHIVED` (case-insensitive). Absent means all
  statuses.

**Behaviour:**
- Page size is fixed at 20.
- Ordered newest-created first (`created_at DESC`).
- Rows carry metadata only; `claude_draft` and `admin_content` are returned by
  the detail endpoint, not the list.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 7,
        "title": "FX–Brief Weekly Recap — Week of May 25, 2026",
        "slug": "week-of-may-25-2026",
        "weekStart": "2026-05-25",
        "weekEnd": "2026-05-29",
        "status": "DRAFT",
        "publishedAt": null,
        "createdAt": "2026-05-30T22:30:11Z",
        "updatedAt": "2026-05-30T22:30:11Z"
      }
    ],
    "totalCount": 7,
    "page": 1,
    "pageSize": 20,
    "totalPages": 1
  },
  "timestamp": "2026-05-30T23:00:00Z"
}
```

**Errors:**
- `400 INVALID_WEEKLY_SUMMARY_STATUS` — unrecognised `status` filter value.
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.

### `GET /admin/weekly-summaries/{id}`

Full admin view of a single summary, including the Claude draft and the
admin-edited content for review.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Path parameters:**
- `id` — the weekly summary id.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "id": 7,
    "title": "FX–Brief Weekly Recap — Week of May 25, 2026",
    "slug": "week-of-may-25-2026",
    "weekStart": "2026-05-25",
    "weekEnd": "2026-05-29",
    "claudeDraft": "The week opened with broad USD strength...",
    "adminContent": null,
    "status": "DRAFT",
    "publishedAt": null,
    "createdAt": "2026-05-30T22:30:11Z",
    "updatedAt": "2026-05-30T22:30:11Z"
  },
  "timestamp": "2026-05-30T23:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.
- `404 WEEKLY_SUMMARY_NOT_FOUND` — no summary with the given id.

### `PUT /admin/weekly-summaries/{id}`

Saves the admin-edited content and, optionally, changes status.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Path parameters:**
- `id` — the weekly summary id.

**Request body:** required.

```json
{
  "adminContent": "The week opened with broad USD strength as...",
  "status": "PUBLISHED"
}
```

**Validation rules:**
- `adminContent` is required, non-blank, max 50000 characters, else
  `400 VALIDATION_FAILED`.
- `status` is optional. When present it must be one of `DRAFT`, `PUBLISHED`,
  `ARCHIVED` (case-insensitive), else `400 INVALID_WEEKLY_SUMMARY_STATUS`.

**Behaviour:**
- `adminContent` is always saved (trimmed).
- When `status` transitions to `PUBLISHED`, `published_at` is set to now and a
  sitemap entry for `/weekly-recap/{slug}` is upserted. Publishing requires
  non-blank admin content, else `409 WEEKLY_SUMMARY_NOT_PUBLISHABLE`.
- When `status` transitions to `ARCHIVED`, the sitemap entry for the path is
  removed.
- A `status` equal to the current status is a no-op for the transition (content
  is still saved).
- `POST .../publish` and `POST .../archive` are the dedicated equivalents and
  apply the identical transition logic.

**Success response (200):** the full `WeeklySummaryDetailView` (same shape as
`GET /admin/weekly-summaries/{id}`) reflecting the saved state.

**Errors:**
- `400 VALIDATION_FAILED` — blank or oversized `adminContent`.
- `400 INVALID_WEEKLY_SUMMARY_STATUS` — unrecognised `status`.
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.
- `404 WEEKLY_SUMMARY_NOT_FOUND` — no summary with the given id.
- `409 WEEKLY_SUMMARY_NOT_PUBLISHABLE` — publish attempted with no admin content.

### `POST /admin/weekly-summaries/{id}/publish`

Publishes a summary: sets status to `PUBLISHED`, stamps `published_at`, and
upserts the sitemap entry for `/weekly-recap/{slug}`.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Path parameters:**
- `id` — the weekly summary id.

**Request body:** none.

**Behaviour:**
- Requires non-blank `admin_content`.
- Idempotent on an already-published row (no second sitemap write of a changed
  timestamp beyond the upsert).

**Success response (200):** the full `WeeklySummaryDetailView` with
`status = "PUBLISHED"` and a populated `publishedAt`.

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.
- `404 WEEKLY_SUMMARY_NOT_FOUND` — no summary with the given id.
- `409 WEEKLY_SUMMARY_NOT_PUBLISHABLE` — no admin content to publish.

### `POST /admin/weekly-summaries/{id}/archive`

Archives a summary: sets status to `ARCHIVED` and removes its sitemap entry.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Path parameters:**
- `id` — the weekly summary id.

**Request body:** none.

**Success response (200):** the full `WeeklySummaryDetailView` with
`status = "ARCHIVED"`.

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.
- `404 WEEKLY_SUMMARY_NOT_FOUND` — no summary with the given id.

## Public endpoints

Unauthenticated, read-only endpoints under `/public`. These are on the
`SecurityConfig` allowlist and require no JWT. CORS applies as for every other
route.

### `GET /public/weekly-summaries`

Paginated list of published weekly recaps for the public recap index.

**Authentication:** none.

**Query parameters:**
- `page` — optional, integer, 1-indexed. Defaults to `1`. Values below `1` are
  clamped to `1`.

**Behaviour:**
- Returns only `PUBLISHED` summaries. Page size is fixed at 20.
- Ordered most-recently-published first (`published_at DESC`).
- `excerpt` is the first 200 characters of `admin_content`.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 7,
        "title": "FX–Brief Weekly Recap — Week of May 25, 2026",
        "slug": "week-of-may-25-2026",
        "weekStart": "2026-05-25",
        "weekEnd": "2026-05-29",
        "publishedAt": "2026-05-31T09:12:00Z",
        "excerpt": "The week opened with broad USD strength as..."
      }
    ],
    "totalCount": 4,
    "page": 1,
    "pageSize": 20,
    "totalPages": 1
  },
  "timestamp": "2026-05-31T10:00:00Z"
}
```

**Errors:** none beyond the standard envelope; an empty archive returns
`items: []`.

### `GET /public/weekly-summaries/{slug}`

Full published recap content by slug.

**Authentication:** none.

**Path parameters:**
- `slug` — the recap slug, e.g. `week-of-may-25-2026`.

**Behaviour:**
- Returns `content` (the admin-edited copy) only. `claude_draft` is never
  exposed publicly.
- A slug that exists but is not `PUBLISHED` returns `404` — the same response as
  an unknown slug, so draft/archived existence is not disclosed.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "id": 7,
    "title": "FX–Brief Weekly Recap — Week of May 25, 2026",
    "slug": "week-of-may-25-2026",
    "weekStart": "2026-05-25",
    "weekEnd": "2026-05-29",
    "publishedAt": "2026-05-31T09:12:00Z",
    "content": "The week opened with broad USD strength as..."
  },
  "timestamp": "2026-05-31T10:00:00Z"
}
```

**Errors:**
- `404 WEEKLY_SUMMARY_NOT_FOUND` — no published summary with the given slug.

### `GET /public/sitemap-entries`

Returns every sitemap entry. The Phase 6A SSR build consumes this list to
generate `sitemap.xml`.

**Authentication:** none.

**Behaviour:**
- Returns all rows, ordered by `path` ascending.
- A weekly summary contributes a row on publish and the row is removed on
  archive. Phase 4D will add article rows to the same registry.

**Success response (200):**

```json
{
  "success": true,
  "data": [
    {
      "path": "/weekly-recap/week-of-may-25-2026",
      "lastModified": "2026-05-31T09:12:00Z",
      "changeFreq": "weekly",
      "priority": 0.7
    }
  ],
  "timestamp": "2026-05-31T10:00:00Z"
}
```

**Errors:** none beyond the standard envelope; an empty registry returns `[]`.

## Articles (Phase 4D)

The article system is admin-managed Markdown content with a public read surface.
Admin endpoints are under `/admin/articles` (role `ADMIN`); public endpoints are
under `/public/articles` (no auth). Content is stored as Markdown and returned
verbatim — rendering is the frontend's responsibility. Reading time is
auto-calculated at save time at 200 words per minute. Slugs are URL-safe,
lowercase, hyphenated, and unique; an explicit slug may be supplied, otherwise
one is derived from the title.

Article categories: `WEEKLY_RECAP`, `EDUCATIONAL`, `FOREX_BASICS`,
`MACRO_INSIGHTS`, `PLATFORM_UPDATES`, `TRADING_PSYCHOLOGY`.

Article statuses: `DRAFT`, `SCHEDULED`, `PUBLISHED`, `ARCHIVED`.

### `GET /admin/articles`

Paginated list of articles for the admin authoring page.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Query parameters:**
- `page` — optional, integer, 1-indexed. Defaults to `1`. Values below `1` are
  clamped to `1`.
- `status` — optional. Filters to one status; must be one of `DRAFT`,
  `SCHEDULED`, `PUBLISHED`, `ARCHIVED` (case-insensitive). Absent means all.
- `category` — optional. Filters to one category (case-insensitive). Absent
  means all.

**Behaviour:**
- Page size is fixed at 20.
- Ordered newest-created first (`created_at DESC`).
- Each row carries the author display name (resolved from `users`), not
  `author_id`. The full Markdown body is omitted from the list; fetch it via the
  detail endpoint.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 12,
        "title": "Understanding Order Blocks",
        "slug": "understanding-order-blocks",
        "excerpt": "A practical guide to identifying institutional order blocks...",
        "category": "EDUCATIONAL",
        "tags": ["smc", "order-blocks"],
        "featuredImageUrl": "https://cdn.fxbrief.example/ob.png",
        "readingTimeMinutes": 6,
        "status": "DRAFT",
        "scheduledPublishAt": null,
        "publishedAt": null,
        "authorName": "Jane Admin",
        "createdAt": "2026-05-30T10:00:00Z",
        "updatedAt": "2026-05-30T10:00:00Z"
      }
    ],
    "totalCount": 1,
    "page": 1,
    "pageSize": 20,
    "totalPages": 1
  },
  "timestamp": "2026-05-31T10:00:00Z"
}
```

**Errors:**
- `400 INVALID_ARTICLE_STATUS` — unrecognised `status` filter value.
- `400 INVALID_ARTICLE_CATEGORY` — unrecognised `category` filter value.
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.

### `GET /admin/articles/{id}`

Full article for editing, including the Markdown body and SEO fields.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Path parameters:**
- `id` — the article id.

**Behaviour:** carries the author display name (from `users`), not `author_id`.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "id": 12,
    "title": "Understanding Order Blocks",
    "slug": "understanding-order-blocks",
    "content": "## What is an order block\n\n...",
    "excerpt": "A practical guide to identifying institutional order blocks...",
    "category": "EDUCATIONAL",
    "tags": ["smc", "order-blocks"],
    "featuredImageUrl": "https://cdn.fxbrief.example/ob.png",
    "readingTimeMinutes": 6,
    "seoTitle": "Understanding Order Blocks | FX–Brief",
    "seoDescription": "Learn to identify institutional order blocks.",
    "status": "DRAFT",
    "scheduledPublishAt": null,
    "publishedAt": null,
    "authorName": "Jane Admin",
    "createdAt": "2026-05-30T10:00:00Z",
    "updatedAt": "2026-05-30T10:00:00Z"
  },
  "timestamp": "2026-05-31T10:00:00Z"
}
```

**Errors:**
- `401 UNAUTHENTICATED` — missing or invalid JWT.
- `403 FORBIDDEN` — caller does not hold the `ADMIN` role.
- `404 ARTICLE_NOT_FOUND` — no article with the given id.

### `POST /admin/articles`

Creates an article. The authenticated admin becomes the author.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Request body:** required.

```json
{
  "title": "Understanding Order Blocks",
  "slug": "understanding-order-blocks",
  "content": "## What is an order block\n\n...",
  "excerpt": "A practical guide...",
  "category": "EDUCATIONAL",
  "tags": ["smc", "order-blocks"],
  "featuredImageUrl": "https://cdn.fxbrief.example/ob.png",
  "seoTitle": "Understanding Order Blocks | FX–Brief",
  "seoDescription": "Learn to identify institutional order blocks.",
  "scheduledPublishAt": null,
  "status": "DRAFT"
}
```

**Validation rules:**
- `title` — required, non-blank, max 255.
- `content` — required, non-blank.
- `category` — required; must be one of the six article categories, else
  `400 INVALID_ARTICLE_CATEGORY`.
- `slug` — optional, max 255. When blank, derived from the title. The supplied
  or derived value is normalised to a URL-safe lowercase hyphenated form and
  must be unique, else `409 ARTICLE_SLUG_CONFLICT`.
- `excerpt` — optional, max 500.
- `tags` — optional array; each tag max 64. Blank tags are dropped; duplicates
  removed. An empty result is stored as null.
- `featuredImageUrl` — optional, max 500.
- `seoTitle` — optional, max 255.
- `seoDescription` — optional, max 500.
- `scheduledPublishAt` — optional ISO-8601 instant.
- `status` — optional; must be one of `DRAFT`, `SCHEDULED`, `PUBLISHED`,
  `ARCHIVED` (case-insensitive), else `400 INVALID_ARTICLE_STATUS`. Defaults to
  `DRAFT`.

**Behaviour:**
- `readingTimeMinutes` is auto-calculated from `content` at 200 words/minute
  (minimum 1).
- If `status` is `PUBLISHED`, `published_at` is stamped now, `scheduledPublishAt`
  is cleared, and a sitemap entry for `/articles/{slug}` is upserted with
  `change_freq = monthly`, `priority = 0.8`.
- If `status` is `SCHEDULED`, the row is left for the scheduled-publish job;
  set `scheduledPublishAt` accordingly.

**Success response (200):** the full `AdminArticleDetailView` (same shape as
`GET /admin/articles/{id}`).

**Errors:**
- `400 VALIDATION_FAILED` — missing/oversized fields.
- `400 INVALID_ARTICLE_CATEGORY` — bad category.
- `400 INVALID_ARTICLE_STATUS` — bad status.
- `409 ARTICLE_SLUG_CONFLICT` — slug already in use.
- `401 UNAUTHENTICATED` / `403 FORBIDDEN`.

### `PUT /admin/articles/{id}`

Updates any subset of fields. A `null` field is left unchanged.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Path parameters:**
- `id` — the article id.

**Request body:** any subset of the create fields. `title` and `content`, when
present, must be non-blank. `slug`, when present and non-blank, is re-normalised
and re-checked for uniqueness. `category` and `status`, when present, are
validated against their enums.

**Behaviour:**
- Changing `content` recomputes `readingTimeMinutes`.
- A `status` transition to `PUBLISHED` stamps `published_at`, clears
  `scheduledPublishAt`, and upserts the sitemap entry (`monthly`/`0.8`); a
  transition to `ARCHIVED` removes the sitemap entry. A `status` equal to the
  current status is a no-op for the transition (other fields are still saved).
- `POST .../publish` and `POST .../archive` are the dedicated equivalents.

**Success response (200):** the full `AdminArticleDetailView` reflecting the
saved state.

**Errors:**
- `400 VALIDATION_FAILED` — blank `title`/`content` or oversized field.
- `400 INVALID_ARTICLE_CATEGORY` / `400 INVALID_ARTICLE_STATUS`.
- `409 ARTICLE_SLUG_CONFLICT` — new slug already in use.
- `404 ARTICLE_NOT_FOUND` — no article with the given id.
- `401 UNAUTHENTICATED` / `403 FORBIDDEN`.

### `POST /admin/articles/{id}/publish`

Publishes an article: sets `status = PUBLISHED`, stamps `published_at = now`
(overriding any `scheduledPublishAt`, which is cleared), and upserts the sitemap
entry for `/articles/{slug}` (`change_freq = monthly`, `priority = 0.8`).

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Request body:** none.

**Success response (200):** the full `AdminArticleDetailView` with
`status = "PUBLISHED"` and a populated `publishedAt`.

**Errors:**
- `404 ARTICLE_NOT_FOUND` — no article with the given id.
- `401 UNAUTHENTICATED` / `403 FORBIDDEN`.

### `POST /admin/articles/{id}/archive`

Archives an article: sets `status = ARCHIVED` and removes its sitemap entry.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Request body:** none.

**Success response (200):** the full `AdminArticleDetailView` with
`status = "ARCHIVED"`.

**Errors:**
- `404 ARTICLE_NOT_FOUND` — no article with the given id.
- `401 UNAUTHENTICATED` / `403 FORBIDDEN`.

### `DELETE /admin/articles/{id}`

Hard-deletes an article. Permitted only when the article is in `DRAFT` status;
a `PUBLISHED` or `ARCHIVED` (or `SCHEDULED`) article must be archived first. No
`sitemap_entries` row exists for a `DRAFT` article, so no sitemap cleanup is
needed.

**Authentication:** required (Bearer JWT, role `ADMIN`)

**Request body:** none.

**Success response (200):**

```json
{
  "success": true,
  "data": null,
  "timestamp": "2026-05-31T10:00:00Z"
}
```

**Errors:**
- `400 ARTICLE_DELETE_NOT_ALLOWED` — article is not in `DRAFT` status.
- `404 ARTICLE_NOT_FOUND` — no article with the given id.
- `401 UNAUTHENTICATED` / `403 FORBIDDEN`.

### `GET /public/articles`

Paginated list of published articles.

**Authentication:** none.

**Query parameters:**
- `page` — optional, integer, 1-indexed. Defaults to `1`. Below `1` is clamped.
- `category` — optional. Filters to one category (case-insensitive), else
  `400 INVALID_ARTICLE_CATEGORY`.
- `tag` — optional. Filters to articles whose `tags` contain the exact value.

**Behaviour:**
- Returns only `PUBLISHED` articles. Page size fixed at 20.
- Ordered most-recently-published first (`published_at DESC`).
- No author details are exposed.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 12,
        "title": "Understanding Order Blocks",
        "slug": "understanding-order-blocks",
        "excerpt": "A practical guide...",
        "category": "EDUCATIONAL",
        "tags": ["smc", "order-blocks"],
        "featuredImageUrl": "https://cdn.fxbrief.example/ob.png",
        "readingTimeMinutes": 6,
        "publishedAt": "2026-05-31T09:00:00Z"
      }
    ],
    "totalCount": 1,
    "page": 1,
    "pageSize": 20,
    "totalPages": 1
  },
  "timestamp": "2026-05-31T10:00:00Z"
}
```

**Errors:**
- `400 INVALID_ARTICLE_CATEGORY` — unrecognised `category` filter value.

### `GET /public/articles/categories`

Lists the categories that have at least one published article, ordered
alphabetically.

**Authentication:** none.

**Success response (200):**

```json
{
  "success": true,
  "data": ["EDUCATIONAL", "MACRO_INSIGHTS"],
  "timestamp": "2026-05-31T10:00:00Z"
}
```

**Errors:** none beyond the standard envelope; an empty set returns `[]`.

### `GET /public/articles/{slug}`

Full published article by slug.

**Authentication:** none.

**Path parameters:**
- `slug` — the article slug.

**Behaviour:**
- Returns the full article minus author internal details.
- A slug that exists but is not `PUBLISHED` returns `404` — the same response as
  an unknown slug, so draft/scheduled/archived existence is not disclosed.

**Success response (200):**

```json
{
  "success": true,
  "data": {
    "id": 12,
    "title": "Understanding Order Blocks",
    "slug": "understanding-order-blocks",
    "content": "## What is an order block\n\n...",
    "excerpt": "A practical guide...",
    "category": "EDUCATIONAL",
    "tags": ["smc", "order-blocks"],
    "featuredImageUrl": "https://cdn.fxbrief.example/ob.png",
    "readingTimeMinutes": 6,
    "seoTitle": "Understanding Order Blocks | FX–Brief",
    "seoDescription": "Learn to identify institutional order blocks.",
    "publishedAt": "2026-05-31T09:00:00Z"
  },
  "timestamp": "2026-05-31T10:00:00Z"
}
```

**Errors:**
- `404 ARTICLE_NOT_FOUND` — no published article with the given slug.

### Scheduled publishing

A background job runs every 15 minutes
(`fxbrief.scheduler.article-publish-cron`, default `0 0/15 * * * *`, UTC). It
finds articles in `SCHEDULED` status whose `scheduled_publish_at` is at or
before now and publishes each one — identical effect to
`POST /admin/articles/{id}/publish` (status `PUBLISHED`, `published_at = now`,
sitemap upsert with `monthly`/`0.8`). The job has no HTTP surface.

### Sitemap note (articles)

Publishing an article upserts a `sitemap_entries` row for `/articles/{slug}`
with `change_freq = monthly` and `priority = 0.8`; archiving removes it. These
rows are returned alongside weekly-recap rows by `GET /public/sitemap-entries`
(documented above). The `sitemap_entries` table is unchanged from Phase 4C.
