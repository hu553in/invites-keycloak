# invites-keycloak

> Invites for your Keycloak. 💌 🗝

Spring Boot service for issuing and consuming Keycloak invitation links. Admins create invites with a limited
lifetime and usage count; recipients redeem them to get an account provisioned with predefined realm roles.

## What the service does

- Admin UI: create, resend, and revoke invites per realm; view status (active, expired, used-up, revoked).
- Invite flow: validate invite token, create Keycloak user, assign realm roles, trigger a required-actions email,
  and mark the invite as used. If any step in this flow fails, created Keycloak users are deleted to keep the flow
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
- Invites: public base URL for generated links, allowed realms with roles, token secret/size/algorithm,
  expiry bounds, and cleanup retention. By design, non-recoverable errors should revoke the invite.
- Mail: SMTP settings are optional; when absent, invite emails are skipped with a warning, and links are still
  displayed.
- Database: PostgreSQL reachable via JDBC; Flyway runs on startup to create the `invite` table and indexes.

### Keycloak setup (all environments)

- Realm: use `master` or set `KEYCLOAK_REALM` to match.
- Client: confidential client named `invites-keycloak` (or set `KEYCLOAK_CLIENT_ID`).
    - Standard Flow enabled.
    - Redirect URIs: `<app-base-url>/login/oauth2/code/keycloak`
        - Example for local: `http://localhost:8080/login/oauth2/code/keycloak`.
    - Web Origins: `<app-base-url>` (add the scheme/port the app is served on).
    - Client secret: copy to `KEYCLOAK_CLIENT_SECRET`.
- Role: realm role `invite-admin` (or `KEYCLOAK_REQUIRED_ROLE`) granted to the user who will sign in to the
  admin UI.
- Token claims: include roles in the ID token. Attach the built-in `roles` client scope or add a mapper for
  `realm_access.roles` (multivalued, in ID token, access token, and userinfo).
- Service account: enable it for the client and grant realm-management roles needed by the backend admin API
  (minimum: `manage-users`, `view-realm` and `manage-realm`). Missing these will cause 403s when listing
  roles or creating users.

### Reverse proxy / HTTPS termination

- The app respects forwarded headers (`server.forward-headers-strategy=framework` is set). Make sure your proxy
  sends them; otherwise, OAuth redirects may downgrade to HTTP.
- Required headers: `Host`, `X-Forwarded-Proto`, `X-Forwarded-Port`, `X-Forwarded-For`.
- nginx example:

```
proxy_set_header Host $host;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Port $server_port;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```

## Local development

- Prerequisites: Java 21, Docker, Docker Compose plugin, and [lefthook](https://github.com/evilmartians/lefthook).
- Install git hooks once: `lefthook install` (pre-commit runs `make check`).
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

1) On the VPS, provision `.env` with Keycloak, invite, mail, and database settings (keep secrets out of the
   compose file).
2) Update `docker-compose.yml` (or use an override file) to point the app image to the GHCR tag you want.
3) Run `docker compose pull && docker compose up -d --wait`.
4) Verify health at the configured healthcheck path (defaults to `/actuator/health`).

## Tech stack

See versions in [libs.versions.toml](gradle/libs.versions.toml) and service wiring in
[docker-compose.yml](docker-compose.yml).

- Java 21, Kotlin 2, Gradle 9, Spring Boot 3
- PostgreSQL 17, Flyway, Spring Data JPA
- Spring Security OAuth2 Client, Thymeleaf, WebFlux (for the Keycloak admin client)
- Detekt, Kover, Testcontainers, WireMock

## Future roadmap

- [x] Add admin logout
- [ ] Add admin invite deletion
- [ ] Timestamps must be shown in the browser's time zone (in the UI and email, if possible)
- [ ] Truncate IDs in the table and show the full ID on hover
- [ ] Test everything and fix all functional issues
    - [ ] All invites must be resendable
    - [ ] "Revoke" button must be disabled for used-up invites
    - [ ] Some issues with roles
    - [ ] Some statuses may not be colored correctly in the table
    - [ ] Some issues with `obtainAccessToken()`
- [ ] Fix all styling issues
- [ ] Add detailed docs
- [ ] Cover everything with logs
- [ ] Add metrics
- [ ] Add tracing
- [ ] Add rate limiting
- [ ] Configure realms for invites through env vars
- [ ] Add i18n
- [ ] Replace `WebClient` with `RestClient` -> remove WebFlux dependency
