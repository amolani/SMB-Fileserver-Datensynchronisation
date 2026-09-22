FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY src/main/java ./src/main/java
COPY scripts/build.sh ./scripts/build.sh
RUN ./scripts/build.sh

FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends rsync openssh-client && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/build/fileserversync.jar /app/application.jar
COPY scripts/healthcheck.sh /usr/local/bin/fileserversync-healthcheck
RUN chmod 0755 /usr/local/bin/fileserversync-healthcheck && mkdir -p /var/lib/fileserversync /root/.ssh
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD /usr/local/bin/fileserversync-healthcheck
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-XX:MaxRAMPercentage=60.0", "-jar", "/app/application.jar"]
