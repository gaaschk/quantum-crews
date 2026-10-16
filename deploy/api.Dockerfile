FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY services/api .
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew bootJar --no-daemon
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/libs/quantum-crews-api.jar app.jar
COPY LICENSE NOTICE /app/
USER 10001:10001
CMD ["java","-jar","app.jar"]
