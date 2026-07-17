# Invites for your Keycloak 💌 🗝

[![CI](https://github.com/hu553in/invites-keycloak/actions/workflows/ci.yml/badge.svg)](https://github.com/hu553in/invites-keycloak/actions/workflows/ci.yml)

Spring Boot service for issuing and consuming invitation links for Keycloak.

Administrators create invite links with limited lifetime, usage count, and predefined realm roles.
Recipients redeem those links to get a Keycloak account, required-actions email, and assigned roles.

## What it does

- Admin UI for creating, resending, revoking, and deleting invites
- Invite status tracking: active, expired, overused, or revoked
- Daily cleanup of expired invites after the configured retention period
- Public invite confirmation page with no `GET` side effects
- CSRF-protected redeem flow with one-time confirmation challenge
- Failure-safe Keycloak provisioning with compensating user deletion
- Localized UI and mail text via `APP_LOCALE` and message bundles

## Requirements

- Java 25
- Bun for repository tooling
- Docker
- Docker Compose plugin
- [prek](https://prek.j178.dev/)

## Setup

1. Copy `.env.example.local` to `.env`.
2. Replace placeholders, especially `KEYCLOAK_URL`, `KEYCLOAK_CLIENT_SECRET`, `INVITE_TOKEN_SECRET`,
   and `INVITE_PUBLIC_BASE_URL`.
3. Run `make run-local`.
4. Run `make test` for tests or `make check` for the full lint/test/coverage gate.

## Invite flow

- `GET /invite/{realm}/{token}` validates the token and renders a confirmation page.
- `POST /invite/{realm}/{token}` redeems the invite, creates the user, assigns realm roles, sends
  the required-actions email, marks the invite as used, and redirects to `/invite/success`.
- `POST` requires a valid CSRF token and a one-time confirmation challenge from the latest `GET`
  page in the current browser session.
- Confirmation challenges expire after 10 minutes and are stored in the server-side HTTP session.
  For multi-instance deployments, use sticky sessions or shared session state.
- If provisioning fails after user creation, the service deletes the created Keycloak user.
  Permanent Keycloak/configuration errors revoke the invite; transient errors leave it retryable.

## Configuration

Configuration is externalized through environment variables using Spring relaxed binding. Prefer
`.env` files over editing `application.yml`; Docker Compose loads `.env` automatically.

Required values:

- `KEYCLOAK_URL`
- `KEYCLOAK_CLIENT_SECRET`
- `INVITE_PUBLIC_BASE_URL`
- `INVITE_TOKEN_SECRET`

Useful defaults:

- Spring profile: `prod`; local examples set `SPRING_PROFILES_ACTIVE=local`
- Keycloak realm: `master`
- Keycloak client ID: `invites-keycloak`
- Required admin role: `invite-admin`
- App locale: `en` (`ru` is also bundled)
- PostgreSQL host/database/user/password: `db` / `invites-keycloak`

Docker Compose accepts `POSTGRES_DB`, `POSTGRES_USER`, and `POSTGRES_PASSWORD` overrides for the
database container. Local app connections can override the default database host with
`POSTGRES_HOSTNAME`.

`KEYCLOAK_URL` must be the Keycloak base URL without `/realms/{realm}`, for example
`https://sso.example.com` or `https://sso.example.com/auth`.

`invite.realms` controls realms shown in the admin UI. Default roles can be set with env variables:

```bash
INVITE_REALMS_MASTER_ROLES_0=invite-admin
INVITE_REALMS_MASTER_ROLES_1=another-role
INVITE_REALMS_PARTNERS_ROLES_0=partner-user
```

Mail is enabled by setting `SPRING_MAIL_HOST` and related `SPRING_MAIL_*` variables. `MAIL_FROM`
sets the optional sender address. API docs and Swagger UI are disabled by default; enable them with:

```bash
SPRINGDOC_API_DOCS_ENABLED=true
SPRINGDOC_SWAGGER_UI_ENABLED=true
```

## Keycloak setup

- Use the `master` realm or set `KEYCLOAK_REALM`.
- Create a confidential client named `invites-keycloak` or set `KEYCLOAK_CLIENT_ID`.
- Enable Standard Flow and service account.
- Set redirect URI to `<app-base-url>/login/oauth2/code/keycloak`.
- Set Web Origins to `<app-base-url>`.
- Copy the client secret to `KEYCLOAK_CLIENT_SECRET`.
- Create realm role `invite-admin` or set `KEYCLOAK_REQUIRED_ROLE`.
- Grant `invite-admin` to users who should access the admin UI.
- Include realm roles in the ID token via the built-in `roles` client scope or a
  `realm_access.roles` mapper.
- Grant the service account `manage-users`, `view-realm`, and `manage-realm` realm-management roles.

Missing service-account roles result in 403 errors when listing roles or creating users.

## Development

- `prek install` installs git hooks.
- `make run-local` starts PostgreSQL with Docker Compose and runs Spring Boot.
- `docker compose up -d db && ./gradlew bootRun` is the faster dev loop after `.env` exists.
- `make test` runs tests.
- `make lint` runs formatting checks and Detekt.
- `make lint-fix` applies formatting and Detekt fixes.
- `make check-config` validates the Docker Compose model without resolving environment values.
- `make check` runs the full local gate.
- `make check-fix` runs the full gate with automatic fixes.
- GitHub Dependabot alerts and security updates monitor dependency vulnerabilities.
- `make build-image` builds `hu553in/invites-keycloak:local`.
- `make run-docker` starts the full Docker Compose stack.
- `make stop-docker` stops it.

## Routes

- `/` redirects to `/admin/invite`.
- `/admin/invite/**` is the authenticated admin UI.
- `/invite/{realm}/{token}` is the public invite page and redeem endpoint.
- `/invite/success` is the public success page after successful redemption.
- `/actuator/health` and `/actuator/prometheus` are public; other actuator endpoints require the
  admin role.

Reverse proxies must send `Host`, `X-Forwarded-Proto`, `X-Forwarded-Port`, and `X-Forwarded-For`
because the app uses forwarded headers for OAuth redirects.

## Deployment

CI publishes `ghcr.io/hu553in/invites-keycloak`:

- `latest` and immutable `sha-*` tags on pushes to `main`
- git tag name on tags matching `v*`

Release helpers run the full local gate before creating a tag:

```bash
make release-patch
make release-minor
make release-major
```

Deployment steps:

1. Provision `.env` with Keycloak, invite, mail, and database settings.
2. Point `compose.yaml` or an override file at the desired image tag.
3. Run:
   ```bash
   docker compose pull && docker compose up -d --wait
   ```
4. Verify `/actuator/health`.

## Tech stack

See exact versions in `gradle/libs.versions.toml` and service wiring in `compose.yaml`.

- Java, Kotlin, Gradle, Spring Boot
- PostgreSQL, Flyway, Spring Data JPA
- Spring Security OAuth2 Client, Thymeleaf
- WebClient for Keycloak Admin REST API
- Micrometer, Prometheus, optional OTLP tracing
- Detekt, Kover, Testcontainers, WireMock
