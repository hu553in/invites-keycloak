build_image:
	./gradlew bootBuildImage \
		--imageName=hu553in/invites-keycloak:1.0.0

run_in_docker: build_image
	docker compose up -d
