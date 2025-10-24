# syntax=docker/dockerfile:1

ARG JAVA_VERSION=21
FROM gradle:9.1.0-jdk${JAVA_VERSION} AS build

WORKDIR /workspace
COPY . .

RUN gradle clean bootJar -x test && java -Djarmode=layertools -jar build/libs/*.jar extract

FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy AS runtime

WORKDIR /app
COPY --from=build /workspace/build/extracted/dependencies/           ./
COPY --from=build /workspace/build/extracted/snapshot-dependencies/  ./
COPY --from=build /workspace/build/extracted/spring-boot-loader/     ./
COPY --from=build /workspace/build/extracted/application/            ./

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
