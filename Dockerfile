FROM maven:3.9.13-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY java-server/pom.xml java-server/mvnw java-server/mvnw.cmd ./
COPY java-server/.mvn .mvn
COPY java-server/src src

RUN chmod +x mvnw && ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/target/app.jar /app/app.jar
COPY entrypoint.sh /app/entrypoint.sh
COPY scripts/hivemem-migrate /usr/local/bin/hivemem-migrate

RUN chmod +x /app/entrypoint.sh /usr/local/bin/hivemem-migrate

EXPOSE 8421
ENTRYPOINT ["/app/entrypoint.sh"]
