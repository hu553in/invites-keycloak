# Project Rules

- Code lives in `src/main/kotlin/com/github/hu553in/invites_keycloak`; tests mirror it under `src/test`.
- Shared assets sit in `src/main/resources`.
- Run locally with `make run_local`.
- Build, test and verify everything via `make check`.
- Integration tests rely on Testcontainers.
- `make run_docker[_rebuild]` runs the composed stack; `make stop_docker` stops it.
- Main tech stack is Kotlin 2 and JVM 21.
- Detekt rules are in `config/detekt/detekt.yml`.
- Keep constructor injection.
- Follow Conventional Commits.
- Link docs/tests to behavior changes.
- Never commit secrets.
- Check dependency health with `./gradlew dependencyUpdates`.
- Implement any new changes as externally configurable through `.env` -> `docker-compose.yml` -> `application.yml`.
- Add only really useful tests — no need to test obvious things just to have such tests.
