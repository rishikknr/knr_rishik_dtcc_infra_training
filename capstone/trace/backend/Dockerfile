
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY backend/pom.xml .
COPY backend/src ./src

RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S trace && adduser -S trace -G trace

COPY --from=build /app/target/*.jar app.jar

USER trace
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
