build_image:
	./gradlew bootBuildImage \
		--imageName=hu553in/invites-keycloak:local

run_in_docker: build_image
	docker compose up -d

check:
	./gradlew check
