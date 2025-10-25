# Repository Guidelines

## Project Structure & Module Organization

- `src/main/kotlin/com/github/hu553in/invites_keycloak` hosts the Spring Boot entry point and feature modules; keep
  packages cohesive.
- `src/main/resources` contains `application.yml`, Flyway migrations under `db/migration`, and Thymeleaf/static assets.
- `src/test/kotlin/com/github/hu553in/invites_keycloak` mirrors the main layout; co-locate integration helpers with
  their specs.
- `config/detekt/detekt.yml` defines style rules; update in step with build reviews.
- `build/` is generated output; treat it as disposable and never check it in.

## Build, Test, and Development Commands

- `./gradlew bootRun` starts the API with live reload-friendly defaults.
- `./gradlew build` compiles, packages, and executes the verification pipeline.
- `./gradlew check` runs tests, Detekt, and Kover verification in one pass.
- `./gradlew detekt` lints Kotlin sources after style-affecting changes.
- `./gradlew koverXmlReport` writes XML coverage reports to `build/reports/kover`.
- `./gradlew koverHtmlReport` writes HTML coverage reports to `build/reports/kover`.
- `make build_image` assembles the container image; `make run_in_docker` starts the compose stack.
- `make check` powers the pre-commit hook.

## Coding Style & Naming Conventions

- Kotlin 2 / JVM 21 is enforced; rely on IDE code style synced with Detekt formatting.
- Use four-space indentation, `UpperCamelCase` for classes, `lowerCamelCase` for functions/fields, and
  `UPPER_SNAKE_CASE` for constants.
- Keep package names lowercase plural (e.g., `invites_keycloak`) and avoid underscores in class names.
- Prefer constructor injection, nullable types only when necessary, and extension functions for shared utilities.

## Testing Guidelines

- Tests live in `src/test/kotlin`; follow `ClassUnderTestTest` naming for units and `*IntegrationTest` for container
  flows.
- JUnit 5, Spring Boot Test, and Testcontainers (PostgreSQL) are available; keep Docker running before integration
  execution.
- Use `./gradlew test` for rapid feedback and `./gradlew check` for the CI matrix; inspect coverage via the Kover
  HTML report.

## Commit & Pull Request Guidelines

- Follow Conventional Commits as reflected in history; scopes optional but welcome.
- Keep commits focused, green, and paired with docs/tests when behavior shifts.
- Pull requests need a concise summary, screenshots for UI tweaks, linked issues, and testing notes.
- Request a reviewer, flag breaking changes, and merge only on green builds.

## Security & Configuration Tips

- Never commit secrets; rely on environment variables or `.env` consumed by `docker-compose.yml`.
- Rotate SMTP and database credentials through deployment tooling, not `application.yml`.
- Review container images and Gradle dependencies regularly with `./gradlew dependencyUpdates` to catch vulnerable
  versions.
