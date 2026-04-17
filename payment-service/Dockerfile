# ---------------------------------------------------------------------------
# payment-service — layered Spring Boot image
# ---------------------------------------------------------------------------
# Stage 1: unpack the fat JAR into Spring Boot layers
FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /builder
COPY target/payment-service-*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract --destination /builder/layers

# Stage 2: assemble the final image with stable layers ordered by change frequency
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 1. Library dependencies (changes least often)
COPY --from=layers /builder/layers/dependencies ./
# 2. Spring Boot launcher (changes with Spring Boot version)
COPY --from=layers /builder/layers/spring-boot-loader ./
# 3. Snapshot dependencies (change rarely)
COPY --from=layers /builder/layers/snapshot-dependencies ./
# 4. Application classes (changes most often — always top layer)
COPY --from=layers /builder/layers/application ./

# Non-root user for security
RUN addgroup -S sentinelpay && adduser -S sentinelpay -G sentinelpay
USER sentinelpay

EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]
