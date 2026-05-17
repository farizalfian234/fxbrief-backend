# FXBrief API Reference

This document is the primary API contract for the FXBrief backend. It is
maintained incrementally as new endpoints are added each phase. Frontend
integration should treat this document as authoritative — Swagger UI is a
secondary view of the same contract.

## Conventions

### Base URL

All endpoints are served from the application root. There is no `/api/v1`
prefix in v1.

### Response envelope

Every JSON response (success or error) uses the following envelope:

```json
{
  "success": true,
  "data": { ... },
  "error": { ... },
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

The `/actuator/health` endpoint is the only documented exception; it follows
Spring Boot's native actuator response shape.

### Error codes

| Code                 | HTTP | Meaning                                               |
| -------------------- | ---- | ----------------------------------------------------- |
| `VALIDATION_FAILED`  | 400  | Request body or query parameters failed validation.   |
| `MALFORMED_REQUEST`  | 400  | Request body could not be parsed.                     |
| `NOT_FOUND`          | 404  | Resource or route does not exist.                     |
| `METHOD_NOT_ALLOWED` | 405  | HTTP method not allowed for this route.               |
| `INTERNAL_ERROR`     | 500  | Unhandled server error. Details written to logs only. |

Additional codes are introduced per phase as features are added.

### Authentication

No endpoints in Phase 1A require authentication. JWT-based authentication is
introduced in Phase 1B.

---

## Endpoints

### `GET /actuator/health`

Health and readiness probe for the application and its database connection.

- **Authentication:** none
- **Request body:** none
- **Response:** Spring Boot actuator native shape.

  ```json
  { "status": "UP" }
  ```

  Status is `UP` when the application is reachable and the database connection
  is available. Detailed component breakdown is intentionally hidden in
  production responses.

- **Errors:** none. The endpoint always returns 200 with a status field.
  Operational tooling should treat any status other than `UP` as unhealthy.

---

### `GET /v3/api-docs`

Returns the generated OpenAPI 3 document for the entire API.

- **Authentication:** none
- **Response:** OpenAPI 3 JSON document.

### `GET /swagger-ui.html`

Renders the Swagger UI for interactive API exploration. Served by
springdoc-openapi.

- **Authentication:** none
- **Response:** HTML page.
