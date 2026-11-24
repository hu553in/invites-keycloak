.DEFAULT_GOAL := check

ACTIONLINT := bunx --minimum-release-age 259200 github-actionlint

.PHONY: lint
lint:
	bun fmt --check
	./gradlew detekt

.PHONY: lint-fix
lint-fix:
	bun fmt
	./gradlew detekt -PdetektAutoCorrect

.PHONY: check-hooks
check-hooks:
	bunx --minimum-release-age 259200 lefthook validate

.PHONY: check-workflows
check-workflows:
	$(ACTIONLINT)

.PHONY: check-config
check-config:
	docker compose --env-file /dev/null -f compose.yaml config --quiet --no-interpolate --no-env-resolution

.PHONY: check
check: check-hooks check-workflows check-config
	bun fmt --check
	./gradlew check

.PHONY: check-fix
check-fix: lint-fix
	$(MAKE) check

.PHONY: test
test:
	./gradlew test

.PHONY: build-image
build-image:
	./gradlew bootBuildImage \
		--imageName=hu553in/invites-keycloak:local

.PHONY: run-docker
run-docker:
	@if [ ! -e .env ] && [ ! -L .env ]; then \
	    (umask 077 && cp .env.example.docker .env); \
	fi
	docker compose up -d --wait

.PHONY: run-docker-rebuild
run-docker-rebuild: build-image
	$(MAKE) run-docker

.PHONY: stop-docker
stop-docker:
	docker compose down

.PHONY: restart-docker
restart-docker: stop-docker
	$(MAKE) run-docker

.PHONY: restart-docker-rebuild
restart-docker-rebuild: stop-docker
	$(MAKE) run-docker-rebuild

.PHONY: logs-docker
logs-docker:
	docker compose logs -f app

.PHONY: run-local
run-local:
	@if [ ! -e .env ] && [ ! -L .env ]; then \
	    (umask 077 && cp .env.example.local .env); \
	fi
	docker compose up -d --wait db
	./gradlew bootRun

.PHONY: release-patch
release-patch: check
	./gradlew release -Prelease.versionIncrementer=incrementPatch

.PHONY: release-minor
release-minor: check
	./gradlew release -Prelease.versionIncrementer=incrementMinor

.PHONY: release-major
release-major: check
	./gradlew release -Prelease.versionIncrementer=incrementMajor
