# FXBrief Backend

Spring Boot backend for FXBrief — AI-powered forex analysis SaaS.

## Stack

- Java 21, Spring Boot 3.3.x, Maven
- PostgreSQL 16 with Flyway migrations
- Lombok, Resilience4j, springdoc-openapi

## Build

```bash
mvn clean package -DskipTests
```

The packaged artifact is `target/fxbrief-backend.jar`.

## Run

The application reads `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` from the
environment. Copy `.env.example` to `.env`, set values, then source it before
launching:

```bash
set -a; source .env; set +a
java -jar target/fxbrief-backend.jar
```

## Endpoints

- `GET /actuator/health` — liveness/readiness
- `GET /swagger-ui.html` — Swagger UI
- `GET /v3/api-docs` — OpenAPI document

See `API.md` for the full API contract.
