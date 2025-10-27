.PHONY: build_image
build_image:
	./gradlew bootBuildImage \
		--imageName=hu553in/invites-keycloak:local

.PHONY: run_docker
run_docker:
	cp --update=none .env.example.docker .env
	docker compose up -d --wait

.PHONY: run_docker_rebuild
run_docker_rebuild: build_image run_docker

.PHONY: stop_docker
stop_docker:
	docker compose down

.PHONY: restart_docker
restart_docker: stop_docker run_docker

.PHONY: restart_docker_rebuild
restart_docker_rebuild: stop_docker run_docker_rebuild

.PHONY: logs_docker
logs_docker:
	docker compose logs -f app

.PHONY: run_local
run_local:
	cp --update=none .env.example.local .env
	docker compose up -d --wait db
	./gradlew bootRun

.PHONY: check
check:
	./gradlew check
