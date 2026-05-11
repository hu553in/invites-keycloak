# Invites for your Keycloak 💌 🗝

[![CI](https://github.com/hu553in/invites-keycloak/actions/workflows/ci.yml/badge.svg)](https://github.com/hu553in/invites-keycloak/actions/workflows/ci.yml)

- [License](./LICENSE)
- [Contributing](./CONTRIBUTING.md)
- [Code of conduct](./CODE_OF_CONDUCT.md)

Spring Boot service for issuing and consuming **invitation links for Keycloak**.

Administrators generate invitation links with a limited lifetime and usage count.
Recipients redeem these links to get a Keycloak account automatically provisioned with
predefined realm roles.

The service keeps invite redemption failure-safe with CSRF protection, one-time confirmation
challenges, and compensating actions for failed Keycloak provisioning.

## What the service does

### Admin side

- Create invites per realm with configurable expiry and usage limits.
- Resend invites, including revoked, expired, or already used ones.
- Revoke and delete invites explicitly.
- Automatically clean up expired invites after the configured retention period (daily job).
- View invite status: active, expired, overused, or revoked.

### Invite flow

- `GET /invite/{realm}/{token}` validates the invitation token and renders a confirmation page.
- `POST /invite/{realm}/{token}` (on form submission) performs the redeem flow:
  - creates a Keycloak user
  - assigns predefined realm roles
  - triggers a required-actions email from Keycloak
  - marks the invite as used
- Successful `POST` requests redirect to `/invite/success` (PRG) to avoid form resubmission on refresh.
- `GET` performs **no side effects**.
- `POST` requires:
  - a valid CSRF token
  - a one-time confirmation challenge issued by the `GET` page
- The confirmation challenge expires after 10 minutes.
  Reopen the invite link to get a fresh confirmation page and challenge.
- Reopening the confirmation page for the same invite in the same browser session invalidates
  previously issued confirmation challenges for that invite (the latest page wins).
- While a redeem `POST` is in progress for an invite, the same browser session cannot issue
  a new confirmation challenge for that invite.
- The confirmation challenge is stored in the server-side HTTP session.
  A small serializable challenge map is stored as a session attribute.
  For multi-instance deployments, configure either session affinity (`sticky sessions`)
  or shared session state (for example, Spring Session with Redis).
- Confirmation challenge and in-flight protections are scoped to a single HTTP session.
  They prevent re-entry and refresh races within the same browser session, but do not
  coordinate different browsers, devices, and sessions using the same invite link at the same time.

The redeem flow (`POST`) uses **compensating actions** to stay failure-safe:

- If any step fails, the created Keycloak user is deleted.
- Permanent errors (for example: missing roles, client-side 4xx from Keycloak) revoke the invite.
- Transient errors keep the invite usable for retry.

## Quick start

1. Copy `.env.example.local` to `.env` and replace placeholder values, especially
   `KEYCLOAK_URL`, `KEYCLOAK_CLIENT_SECRET`, `INVITE_TOKEN_SECRET`, and `INVITE_PUBLIC_BASE_URL`.
2. Run `make run-local` (starts PostgreSQL via Docker Compose, then the application).
3. For dev loop, keep `docker compose up -d db` running and start the app with `./gradlew bootRun`.
4. Run `make test` (unit + integration) or `make check` (full linting and coverage).

## Architecture

- Spring Boot MVC with Thymeleaf
- Keycloak Admin REST API via reactive WebClient
- PostgreSQL + Flyway
- Invite tokens stored as salted hash (raw tokens never persisted)
- Structured logging, sensitive values masked

## Configuration and environment

### General principles

- All configuration is externalized via environment variables using Spring relaxed binding.
- Prefer `.env` files over editing `application.yml`.
- Docker Compose loads `.env` automatically via `env_file: .env`.

The bundled `.env.example.local` and `.env.example.docker` files:

- are meant for local development and demonstration
- are not exhaustive lists of all available configuration options
- contain placeholder values (for example, `KEYCLOAK_URL=https://id.example.com`) that must be replaced

Copy one of them to `.env` and adjust it for the target environment.

### Localization

User-facing locale is controlled by `APP_LOCALE` (`en` or `ru`, default `en`). Add new locales via
`messages_*.properties` files and rebuild.

### Spring profiles

- Default profile: `prod`
- Local development: set `SPRING_PROFILES_ACTIVE=local` in `.env`
  (the example files already do this)

### Keycloak configuration

Required:

- `KEYCLOAK_URL`
- `KEYCLOAK_CLIENT_SECRET`

`KEYCLOAK_URL` must be the **Keycloak base URL** (scheme, host, and optional port or base path),
without the realm suffix (do not append `/realms/{realm}`).

Examples:

- `https://sso.example.com`
- `https://sso.example.com/auth` (if Keycloak is served under a base path)

The application builds the OIDC issuer URL as `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}` and
resolves OIDC discovery during startup, so `KEYCLOAK_URL` must be reachable from the runtime
environment (host process or container) and use a trusted certificate if HTTPS is enabled.

Defaults (override via env if needed):

- Realm: `master`
- Client ID: `invites-keycloak`
- Required admin role: `invite-admin`
- HTTP timeouts:
  - Connect: 5 seconds
  - Response: 10 seconds

### Invite configuration

Required:

- `INVITE_PUBLIC_BASE_URL`
- `INVITE_TOKEN_SECRET`

Defaults for expiry bounds, token size, salt, MAC algorithm, realm mapping, and cleanup retention
are defined in `application.yml` and can be overridden via environment variables.

`invite.realms` is the allowlist of realms available in the admin UI.
`invite.realms.<realm>.roles` defines the default (preselected) realm roles for invites in that realm.

Example env overrides:

- `INVITE_REALMS_MASTER_ROLES_0=invite-admin`
- `INVITE_REALMS_MASTER_ROLES_1=another-role`
- `INVITE_REALMS_PARTNERS_ROLES_0=partner-user`

### Mail configuration

- Enable mail by setting `SPRING_MAIL_HOST` (and related `SPRING_MAIL_*` variables).
- `MAIL_FROM` is optional.
- `MAIL_SUBJECT_TEMPLATE` is optional.
- If `MAIL_SUBJECT_TEMPLATE` is blank, the default localized subject from the message bundle is used.

To disable mail entirely, set:

- `SPRING_AUTOCONFIGURE_EXCLUDE` to
  `org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration,org.springframework.boot.autoconfigure.mail.MailSenderValidatorAutoConfiguration`

### SpringDoc configuration

- API docs and Swagger UI are disabled by default.
- Enable by setting both variables to `true`:
  - `SPRINGDOC_API_DOCS_ENABLED=true`
  - `SPRINGDOC_SWAGGER_UI_ENABLED=true`

### Database configuration

Defaults point to PostgreSQL running at `db:5432` with:

- database: `invites-keycloak`
- user: `invites-keycloak`
- password: `invites-keycloak`

Override using:

- `POSTGRES_HOSTNAME`
- `POSTGRES_PORT`
- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`

## Keycloak setup (all environments)

### Realm

- Use the `master` realm or set `KEYCLOAK_REALM` explicitly.

### Client

- Confidential client named `invites-keycloak` (or override with `KEYCLOAK_CLIENT_ID`).
- Standard Flow enabled.
- Redirect URI:
  - `<app-base-url>/login/oauth2/code/keycloak`
  - Example (local): `http://localhost:8080/login/oauth2/code/keycloak`
- Web Origins:
  - `<app-base-url>` (including scheme and port)
- Copy the client secret to `KEYCLOAK_CLIENT_SECRET`.

### Role

- Realm role `invite-admin` (or override via `KEYCLOAK_REQUIRED_ROLE`).
- Grant this role to users who should access the admin UI.

### Token claims

Roles must be included in the ID token. Attach the built-in `roles` client scope or add a
`realm_access.roles` mapper (multivalued, included in ID tokens, access tokens, and user info).

### Service account

- Enable the service account for the client.
- Grant the following realm-management roles at minimum:
  - `manage-users`
  - `view-realm`
  - `manage-realm`

Missing roles will result in 403 errors when listing roles or creating users.

## Reverse proxy and HTTPS termination

The application respects forwarded headers (`server.forward-headers-strategy=framework`).
Ensure the reverse proxy sends `Host`, `X-Forwarded-Proto`, `X-Forwarded-Port`, and `X-Forwarded-For`.
Without correct forwarding, OAuth redirects may downgrade to HTTP.

## Local development

### Prerequisites

- Java 25
- Docker
- Docker Compose plugin
- [prek](https://prek.j178.dev/)

### Setup

- Install git hooks once:
  ```bash
  prek install
  ```
- Before the first start, replace placeholder values in `.env`, especially:
  - `KEYCLOAK_URL`
  - `KEYCLOAK_CLIENT_SECRET`
  - `INVITE_TOKEN_SECRET`
  - `INVITE_PUBLIC_BASE_URL`
- Run locally (starts PostgreSQL via Docker Compose, then Spring Boot):
  ```bash
  make run-local
  ```
- Fast dev loop:
  - keep `docker compose up -d db` running
  - start the app with `./gradlew bootRun`
- Tests:
  - unit and integration tests: `make test`
  - full linting and coverage: `make check`

## Routes and UI

- `/`: redirects to `/admin/invite` (authentication required).

- `/admin/invite/**`: admin UI for creating, resending, revoking, and deleting invites.
  Protected by Keycloak OAuth2 login.

- `/invite/{realm}/{token}`: public invite endpoint; also accepts a trailing slash for both `GET` and `POST`.
  - `GET` renders a minimal confirmation page (no side effects)
  - `POST` redeems the invite after explicit confirmation (`CSRF` + one-time challenge)
    and redirects to `/invite/success`

- `/invite/success`: public success page used as the redirect target after successful invite redemption.

Admin pages include a logout action that signs out of Keycloak and returns to the start page.

## Deploying to a VPS with Docker

CI builds and pushes images to:

- `ghcr.io/hu553in/invites-keycloak`

Published tags:

- `latest` and commit SHA on pushes to `main`
- git tag name (for example, `v1.2.3`) when the tag matches `v*`

Deployment steps:

1. Provision a `.env` file on the VPS with Keycloak, invite, mail, and database settings.
2. Update `docker-compose.yml` (or an override file) to reference the desired image tag.
3. Run:
   ```bash
   docker compose pull && docker compose up -d --wait
   ```
4. Verify service health at the configured health endpoint (default: `/actuator/health`).

## Tech stack

See exact versions in `gradle/libs.versions.toml` and service wiring in `docker-compose.yml`.

- Java, Kotlin, Gradle, Spring Boot
- PostgreSQL, Flyway, Spring Data JPA
- Spring Security OAuth2 Client, Thymeleaf
- WebClient (reactive) for Keycloak admin API
- Micrometer with Prometheus registry
- Micrometer tracing (OTLP exporter optional)
- Detekt, Kover, Testcontainers, WireMock

## Observability

- Public endpoints: `/actuator/health`, `/actuator/prometheus`
- Other actuator endpoints require the `invite-admin` role
- Prometheus scraping enabled by default
- OTLP tracing available (disabled by default, enable via `MANAGEMENT_TRACING_EXPORT_ENABLED=true`)

### Logging

Structured logging via SLF4J. Sensitive values masked by default. Service layer owns `INFO`-level
audit logs for invite lifecycle. See source code for detailed log level policy.
