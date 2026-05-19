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
| `PASSWORD_RESET_UNAVAILABLE`  | 400  | Account has no password (e.g. Google-only). |
| `UNAUTHENTICATED`             | 401  | Authentication is required and was missing or invalid. |
| `INVALID_CREDENTIALS`         | 401  | Email and password combination did not match. |
| `ACCOUNT_NOT_VERIFIED`        | 403  | Account exists but email has not yet been verified. |
| `ACCOUNT_INACTIVE`            | 403  | Account is inactive for reasons other than unverified email. |
| `FORBIDDEN`                   | 403  | Authenticated but not authorised for this resource. |
| `NOT_FOUND`                   | 404  | Resource or route does not exist. |
| `METHOD_NOT_ALLOWED`          | 405  | HTTP method not allowed for this route. |
| `EMAIL_ALREADY_REGISTERED`    | 409  | An account with this email already exists. |
| `RATE_LIMIT_EXCEEDED`         | 429  | Too many requests from this IP within the rate-limit window. A `Retry-After` header indicates the number of seconds to wait. |
| `INTERNAL_ERROR`              | 500  | Unhandled server error. Details written to logs only. |

Additional codes are introduced per phase as features are added.

### Authentication

Authenticated endpoints require a JWT in the `Authorization` header:

```
Authorization: Bearer <jwt>
```

Tokens are HS256-signed, issued on successful login, and valid for 24 hours. There is no
refresh-token flow in v1; clients re-authenticate when the token expires.

### Rate limiting

A subset of unauthenticated auth endpoints is rate-limited per client IP using a fixed
60-second window:

| Endpoint                  | Limit per IP per minute |
|---------------------------|-------------------------|
| `POST /auth/login`        | 5                       |
| `POST /auth/register`     | 3                       |
| `POST /auth/forgot-password` | 3                    |

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
  returned.
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

Authenticates a user and issues a JWT.

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
- If no user exists or the password does not match,
  `INVALID_CREDENTIALS` is returned.
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
- `401 INVALID_CREDENTIALS` — wrong email or password.
- `403 ACCOUNT_NOT_VERIFIED` — account exists but email is not yet verified.
- `403 ACCOUNT_INACTIVE` — account inactive for another reason.
- `429 RATE_LIMIT_EXCEEDED` — more than 5 login attempts from this IP in the last minute.

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
- If the account exists but has no password (Google-only account),
  `PASSWORD_RESET_UNAVAILABLE` is returned with the message: "This account uses
  Google login. Password reset is not available."
- Otherwise a 256-bit token is generated, its SHA-256 hash is persisted with a 30-minute
  TTL, and the resulting reset link is written to the application log at `INFO` level. Email
  delivery via SendGrid is introduced in Phase 5A. The raw token is never returned to the
  client.

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
- `400 PASSWORD_RESET_UNAVAILABLE` — account has no password.
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
  the token's `used_at` is stamped with the current time.

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
- Once the grace period has elapsed, the user, the user's verification tokens, and the
  user's password-reset tokens are deleted from the database by the daily scheduler
  (`ON DELETE CASCADE`).

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
