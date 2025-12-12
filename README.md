# Invites for your Keycloak 💌 🗝

[![CI](https://github.com/hu553in/invites-keycloak/actions/workflows/ci.yml/badge.svg)](https://github.com/hu553in/invites-keycloak/actions/workflows/ci.yml)

- [License](./LICENSE)
- [How to contribute](./CONTRIBUTORS.md)

---

Spring Boot service for issuing and consuming Keycloak invitation links. Admins create invites with a limited lifetime
and usage count; recipients redeem them to get an account provisioned with predefined realm roles.

## What the service does

- Admin UI: create, resend (including revoked/expired/used-up), revoke, and delete invites per realm; delete them after
  revoke/expiry/usage; view status (active, expired, used-up, revoked).
- Invite flow: validate invite token, create Keycloak user, assign realm roles, trigger a required-actions email, and
  mark the invite as used. If any step in this flow fails, created Keycloak users are deleted to keep the flow atomic.
  Permanent errors (for example, missing roles or client-side 4xx from Keycloak) revoke the invite; transient errors
  keep the invite usable.
- Housekeeping: expired invites beyond retention are purged by a daily scheduled job.

## Architecture at a glance

- Spring Boot MVC + Thymeleaf (server-rendered admin & public views).
- Keycloak admin REST calls via reactive `WebClient` with retries for transient failures.
- PostgreSQL + Flyway for persistence; invites stored with token hash + salt.
- Strict input normalization (trimming, lowercasing emails) and masked logging of sensitive values.
- Structured logging uses SLF4J event builders; a servlet filter puts `current_user.id` (username or `system`) and
  `current_user.sub` (OIDC subject when available) into MDC for every request.

## Configuration and environment

- Everything is externalizable via environment variables thanks to Spring relaxed binding; prefer `.env` over editing
  `src/main/resources/application.yml`. Compose loads `.env` via `env_file: .env`.
- The bundled `.env.example.local` and `.env.example.docker` are for local development and showcasing only; they are not
  exhaustive lists of tunables. Copy one to `.env` and adjust for your setup.
- Profiles: `application.yml` defaults to `prod`. Set `SPRING_PROFILES_ACTIVE=local` in your `.env` for local runs (the
  examples do this).
- Keycloak: `KEYCLOAK_URL` and `KEYCLOAK_CLIENT_SECRET` are required. Defaults for realm/client ID/required role are
  `master`/`invites-keycloak`/`invite-admin`; HTTP timeouts default to 5s connect and 10s response. Override any of
  these via env if needed.
- Invites: `INVITE_PUBLIC_BASE_URL` and `INVITE_TOKEN_SECRET` are required. Other invite defaults (expiry bounds, token
  bytes/salt, MAC algorithm, realms map, cleanup retention) live in `application.yml` and can be overridden via env
  vars.
- Mail: enable by providing `SPRING_MAIL_HOST` (and related `SPRING_MAIL_*` as needed). `MAIL_FROM` is optional;
  subject template defaults to `Invitation to %s`. To disable mail entirely, set `SPRING_AUTOCONFIGURE_EXCLUDE` to
  `org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration,org.springframework.boot.autoconfigure.mail.MailSenderValidatorAutoConfiguration`.
- Database: defaults point to Postgres at `db:5432` with database/user/password `invites-keycloak`. Override via
  `POSTGRES_HOSTNAME`/`POSTGRES_PORT`/`POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD` env vars.

### Keycloak setup (all environments)

- Realm: use `master` or set `KEYCLOAK_REALM` to match.
- Client: confidential client named `invites-keycloak` (or set `KEYCLOAK_CLIENT_ID`).
    - Standard Flow enabled.
    - Redirect URIs: `<app-base-url>/login/oauth2/code/keycloak`
        - Example for local: `http://localhost:8080/login/oauth2/code/keycloak`.
    - Web Origins: `<app-base-url>` (add the scheme/port the app is served on).
    - Client secret: copy to `KEYCLOAK_CLIENT_SECRET`.
- Role: realm role `invite-admin` (or `KEYCLOAK_REQUIRED_ROLE`) granted to the user who will sign in to the admin UI.
- Token claims: include roles in the ID token. Attach the built-in `roles` client scope or add a mapper for
  `realm_access.roles` (multivalued, in ID token, access token, and userinfo).
- Service account: enable it for the client and grant realm-management roles needed by the backend admin API (minimum:
  `manage-users`, `view-realm`, and `manage-realm`). Missing these will cause 403s when listing roles or creating users.

### Reverse proxy / HTTPS termination

- The app respects forwarded headers (`server.forward-headers-strategy=framework` is set). Make sure your proxy sends
  them; otherwise, OAuth redirects may downgrade to HTTP.
- Required headers: `Host`, `X-Forwarded-Proto`, `X-Forwarded-Port`, `X-Forwarded-For`.
- nginx example:
  ```
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-Proto $scheme;
  proxy_set_header X-Forwarded-Port $server_port;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  ```

## Local development

- Prerequisites: Java 21, Docker, Docker Compose plugin, and [pre-commit](https://pre-commit.com/).
- Install git hooks once: `pre-commit install` (pre-commit runs `make check` and a few other checks).
- Run the app locally (starts Postgres via Compose, then Boot): `make run_local`.
- Fast dev loop: keep `docker compose up -d db` running; use `./gradlew bootRun` for hot restarts.
- Tests: `make test` (unit/integration). Full linting + coverage: `make check`.

### Routes and UI

- `/` redirects to `/admin/invite` (login required).
- `/admin/invite/**` is the admin UI for creating/resending/revoking invites; protected via Keycloak OAuth2 login.
- `/invite/{realm}/{token}` is public: validates the token, creates the Keycloak user, assigns roles, sends a
  required-actions email, and marks the invite as used.
- Admin pages include a logout action that signs out of Keycloak and returns to the start page.

## Deploying to a VPS with Docker

CI builds and pushes images to `ghcr.io/hu553in/invites-keycloak`:

- `latest` and commit SHA on pushes to `main`.
- git tag name (for example `v1.2.3`) when the tag matches `v*`.

To deploy:

1) On the VPS, provision `.env` with Keycloak, invite, mail, and database settings (keep secrets out of the compose
   file).
2) Update `docker-compose.yml` (or use an override file) to point the app image to the GHCR tag you want.
3) Run `docker compose pull && docker compose up -d --wait`.
4) Verify health at the configured health check path (defaults to `/actuator/health`).

## Tech stack

See versions in [libs.versions.toml](gradle/libs.versions.toml) and service wiring in
[docker-compose.yml](docker-compose.yml).

- Java 21, Kotlin 2, Gradle 9, Spring Boot 3
- PostgreSQL 17, Flyway, Spring Data JPA
- Spring Security OAuth2 Client, Thymeleaf, WebFlux (for the Keycloak admin client)
- Micrometer + Prometheus registry, Micrometer tracing (OTLP exporter optional)
- Detekt, Kover, Testcontainers, WireMock

## Observability

- Actuator: `/actuator/health` and `/actuator/prometheus` are public; all other actuator endpoints require the Keycloak
  `invite-admin` role. Configure your proxy/network accordingly.
- Metrics: Prometheus scrape is enabled by default.
- Tracing: OTLP exporter dependency is present but disabled by default (`management.tracing.export.enabled=false`). To
  emit spans, set `MANAGEMENT_TRACING_EXPORT_ENABLED=true` and configure `MANAGEMENT_OTLP_TRACING_ENDPOINT`
  (for example `http://otel-collector:4318/v1/traces`). Adjust sampling with `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`.
- Logging conventions:
    - Servlet access log (opt-in: `access-logging.enabled=true`) emitted once per request with method, path, status,
      duration, and MDC-enriched user/invite context (PII stays masked).
    - Service layer owns INFO/audit logs for invite lifecycle (create/resend/revoke/delete/use) and includes the actor;
      controllers avoid duplicating success logs.
    - Keycloak admin client logs HTTP failures with status, context, and duration; retries are logged at DEBUG with
      counts; controller advice adds route/status so requests are traceable without duplicating client details.
    - When handling `KeycloakAdminClientException` outside the client, use `log.dedupedEventForInviteError(...)` so
      upper layers don't double-log failures already captured in the client.
    - Log level policy: client-side/validation issues -> WARN (except public invite validation/not-found handled at
      DEBUG to avoid noise); Keycloak 4xx -> WARN; server/misconfig -> ERROR; routine reads/validation -> DEBUG; state
      changes/audit -> INFO. Always mask emails via `maskSensitive` to keep PII out of logs and use MDC helpers
      (`withAuthDataInMdc`, `withInviteContextInMdc`).

## Remaining urgent tasks

- [ ] Add any missing important info to this file
- [ ] Add i18n
- [ ] Replace `WebClient` with `RestClient` -> remove WebFlux dependency (optional)
