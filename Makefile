.DEFAULT_GOAL := check

PRETTIER := bunx prettier -u
ACTIONLINT := bunx github-actionlint
TAPLO := bunx @taplo/cli
PREK ?= prek

.PHONY: build-image
build-image:
	./gradlew bootBuildImage \
		--imageName=hu553in/invites-keycloak:local

.PHONY: run-docker
run-docker:
	@if [ ! -e .env ]; then \
	    cp .env.example.docker .env; \
	fi
	docker compose up -d --wait

.PHONY: run-docker-rebuild
run-docker-rebuild: build-image run-docker

.PHONY: stop-docker
stop-docker:
	docker compose down

.PHONY: restart-docker
restart-docker: stop-docker run-docker

.PHONY: restart-docker-rebuild
restart-docker-rebuild: stop-docker run-docker-rebuild

.PHONY: logs-docker
logs-docker:
	docker compose logs -f app

.PHONY: run-local
run-local:
	@if [ ! -e .env ]; then \
	    cp .env.example.local .env; \
	fi
	docker compose up -d --wait db
	./gradlew bootRun

.PHONY: lint
lint:
	$(PRETTIER) -c .
	$(TAPLO) fmt --check
	./gradlew detekt

.PHONY: lint-fix
lint-fix:
	$(PRETTIER) -w .
	$(TAPLO) fmt
	./gradlew detekt -PdetektAutoCorrect

.PHONY: check-config
check-config:
	docker compose config --quiet --no-interpolate --no-env-resolution

.PHONY: check-workflows
check-workflows:
	$(ACTIONLINT)

.PHONY: check-renovate
check-renovate:
	bunx --package renovate renovate-config-validator --strict --no-global renovate.json

.PHONY: check-hooks
check-hooks:
	$(PREK) validate-config prek.toml

.PHONY: check
check: check-hooks check-renovate check-workflows
	$(PRETTIER) -c .
	$(TAPLO) fmt --check
	$(MAKE) check-config
	./gradlew check

.PHONY: check-fix
check-fix: lint-fix
	$(MAKE) check

.PHONY: test
test:
	./gradlew test

.PHONY: release-patch
release-patch: check
	./gradlew release -Prelease.versionIncrementer=incrementPatch

.PHONY: release-minor
release-minor: check
	./gradlew release -Prelease.versionIncrementer=incrementMinor

.PHONY: release-major
release-major: check
	./gradlew release -Prelease.versionIncrementer=incrementMajor
