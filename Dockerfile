# Web version for Render (or any Docker host). Compiles the Java sources and runs the built-in Java web server.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY lib/*.jar lib/
COPY src/main/java src/main/java
RUN javac --release 21 -encoding UTF-8 -d out -cp "lib/*" -sourcepath src/main/java src/main/java/com/hospital/web/WebServer.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out out
COPY lib/*.jar lib/
# SQLite inside the container by default. For data that survives restarts, set HOSPITAL_DB_MODE=mysql
# and HOSPITAL_DB_URL / HOSPITAL_DB_USER / HOSPITAL_DB_PASSWORD in the Render dashboard.
ENV HOSPITAL_DB_MODE=sqlite \
    HOSPITAL_DB_FILE=/app/data/hospital_triage.db \
    PORT=8080
EXPOSE 8080
CMD ["java", "-Xmx384m", "-cp", "out:lib/*", "com.hospital.web.WebServer"]
