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
  written to the application log at `INFO` level. Email delivery via SendGrid is introduced
  in Phase 5A.

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
     null, `role = USER`, and the Google link is inserted.

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
  link is written to the application log at `INFO` level. Email delivery via SendGrid is
  introduced in Phase 5A. The raw token is never returned to the client.
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
- `hasEverPaid` is read from the `users` row. Phase 5B will be responsible for flipping it
  on first successful payment; in Phase 2A and Phase 2B the value is always `false` for
  any newly registered user.
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

Initiates a top-up for the authenticated user. Returns the carry-over preview the
frontend uses to render the warning modal before redirecting to Midtrans.

**This endpoint does not perform a payment.** The actual plan change, report-count
mutation, and audit row are written by the Phase 5B Midtrans confirmation handler. The
Phase 2B endpoint is a read-only preparation step that surfaces the carry-over
calculation and warning flag to the frontend so the right UX can be shown before
payment.

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
- The authenticated user's current `subscriptions` row is read. `remainingReports` is
  preserved in the response as the carry-over base; per PRD §5.7, remaining reports
  always carry over and are never lost on a plan switch.
- `carryOverCalculation` is computed as `{ remainingReports, additionalReports, newTotal }`
  where `additionalReports` is the target plan's `report_count` (20 for both Basic and
  Premium) and `newTotal = remainingReports + additionalReports`.
- `warningFlag` is `true` when `remainingReports > 0`. The frontend uses this to decide
  whether to show the carry-over warning modal before redirecting to payment (PRD §5.7).
  A Free user and a lapsed user with 0 remaining both receive `warningFlag = false` and
  no warning modal is shown.
- `paymentUrl` is `null` in this phase. Midtrans charge creation lands in Phase 5B and
  will populate this field with the hosted payment-page URL. The field is in the
  response shape now so the frontend integration contract is stable across the Phase 2B /
  Phase 5B transition.
- No subscription state is mutated. No `subscription_audit_logs` row is written — the
  audit row corresponding to a top-up is emitted by Phase 5B when payment is confirmed
  and the plan/remaining count actually change.

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
    "paymentUrl": null,
    "warningFlag": true,
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
- `404 NOT_FOUND` — no subscription exists for the authenticated user (see notes on
  `GET /subscription`).
