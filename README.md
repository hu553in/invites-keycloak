# invites-keycloak

> Invites for your Keycloak. 💌 🗝

Spring Boot service that streamlines issuing invitation links for Keycloak realms.

## Tech Stack

> Check [libs.versions.toml](gradle/libs.versions.toml) and [docker-compose.yml](docker-compose.yml) for details.

- Kotlin 2
- Gradle 9
- Spring Boot 3
- PostgreSQL 17

## Roadmap

- [ ] Implement an MVP
- [ ] Fill readme
- [ ] Handle all TODOs
- [ ] Metrics
- [ ] OpenTelemetry
- [ ] Log all requests
- [ ] Rate limiting
- [ ] Configure realms for invites through environment variables
- [ ] Internationalization
- [ ] Track the account creation progress with a "restore checkpoint" possibility
- [ ] `WebClient` -> `RestClient` -> remove WebFlux dependency
