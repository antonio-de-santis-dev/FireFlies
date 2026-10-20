# Immagine di produzione del backend (FASE 8). Build multi-stage: nel risultato solo il JRE e il jar, nessun sorgente,
# nessuna credenziale (tutte da variabili d'ambiente al runtime, vedi infra/.env.prod.example).
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/fireflies-backend-*.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S fireflies && adduser -S -G fireflies -H fireflies && apk add --no-cache curl
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER fireflies
ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
