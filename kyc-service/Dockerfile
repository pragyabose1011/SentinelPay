# ---------------------------------------------------------------------------
# kyc-service — layered Spring Boot image
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /builder
COPY target/kyc-service-*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract --destination /builder/layers

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=layers /builder/layers/dependencies ./
COPY --from=layers /builder/layers/spring-boot-loader ./
COPY --from=layers /builder/layers/snapshot-dependencies ./
COPY --from=layers /builder/layers/application ./

RUN addgroup -S sentinelpay && adduser -S sentinelpay -G sentinelpay
USER sentinelpay

EXPOSE 8083

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]
