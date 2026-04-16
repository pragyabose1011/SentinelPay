FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/api-gateway-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
