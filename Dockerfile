FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

COPY --from=build /workspace/target/*.jar /app/app.jar
RUN mkdir -p /app/logs

ENV RD_LOG_PATH=/app/logs

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
