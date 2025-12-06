# invites-keycloak

> Invites for your Keycloak. 💌 🗝

Spring Boot service for issuing and consuming Keycloak invitation links. Admins create invites with a limited
lifetime and usage count; recipients redeem them to get an account provisioned with predefined realm roles.

## What the service does

- Admin UI: create, resend, and revoke invites per realm; view status (active, expired, used-up, revoked).
- Invite flow: validate invite token, create Keycloak user, assign realm roles, trigger required actions email,
  and mark invite as used. If any step in this flow fails, created Keycloak users are deleted to keep the flow
  atomic. Permanent errors (for example missing roles or client-side 4xx from Keycloak) revoke the invite;
  transient errors keep the invite usable.
- Housekeeping: expired invites beyond retention are purged by a daily scheduled job.

## Architecture at a glance

- Spring Boot MVC + Thymeleaf (server-rendered admin & public views).
- Keycloak admin REST calls via reactive `WebClient` with retries for transient failures.
- PostgreSQL + Flyway for persistence; invites stored with token hash + salt.
- Strict input normalization (trimming, lowercasing emails) and masked logging of sensitive values.

## Configuration expectations

- Keycloak: an admin client with client credentials, required admin role name, and reachable issuer URL.
- Invites: public base URL for generated links, allowed realms with default roles, token secret/size/algorithm,
  expiry bounds, and cleanup retention. Errors that are not recoverable should revoke the invite by design.
- Mail: SMTP settings optional; when absent, invite emails are skipped with a warning, links still displayed.
- Database: PostgreSQL reachable via JDBC; Flyway runs on startup to create the `invite` table and indexes.

## Local development

- Prerequisites: Java 21, Docker, Docker Compose plugin, and [lefthook](https://github.com/evilmartians/lefthook).
- Install git hooks once: `lefthook install` (pre-commit runs `make check`).
- Run the app locally (starts Postgres via Compose, then Boot): `make run_local`.
- Fast dev loop: keep `docker compose up -d db` running; use `./gradlew bootRun` for hot restarts.
- Tests: `make test` (unit/integration). Full lint + coverage: `make check`.

## Deploying to a VPS with Docker

CI builds and pushes images to `ghcr.io/hu553in/invites-keycloak`:
- `latest` and commit SHA on pushes to `main`.
- git tag name (for example `v1.2.3`) when the tag matches `v*`.

To deploy:

1) On the VPS, provision `.env` with Keycloak, invite, mail, and database settings (no secrets in compose file).
2) Update `docker-compose.yml` (or use an override file) to point the app image to the GHCR tag you want.
3) Run `docker compose pull && docker compose up -d --wait`.
4) Verify health at the configured healthcheck path (defaults to `/actuator/health`).

## Tech stack

See versions in [libs.versions.toml](gradle/libs.versions.toml) and service wiring in
[docker-compose.yml](docker-compose.yml).

- Java 21, Kotlin 2, Gradle 9, Spring Boot 3
- PostgreSQL 17, Flyway, Spring Data JPA
- Spring Security OAuth2 Client, Thymeleaf, WebFlux (Keycloak admin client)
- Detekt, Kover, Testcontainers, WireMock

## Future roadmap

- [ ] Add detailed docs
- [ ] Cover everything with logs
- [ ] Add metrics
- [ ] Add tracing
- [ ] Add rate limiting
- [ ] Configure realms for invites through env vars
- [ ] Add i18n
- [ ] Replace `WebClient` with `RestClient` -> remove WebFlux dependency
