# ---------- Build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/ ./backend/
RUN mvn -f backend/stock-parent/pom.xml -DskipTests package

# ---------- Run ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/backend/stock-api/target/stock-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
