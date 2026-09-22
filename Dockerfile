FROM eclipse-temurin:17-jdk AS build

WORKDIR /src
COPY src/main/java ./src/main/java
RUN mkdir -p /out/classes && \
    javac -encoding UTF-8 -d /out/classes $(find src/main/java -name '*.java') && \
    jar --create --file /out/fileserversync.jar --main-class org.example.Main -C /out/classes .

FROM eclipse-temurin:17-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends rsync openssh-client procps && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /out/fileserversync.jar /app/application.jar
COPY scripts/healthcheck.sh /usr/local/bin/fileserversync-healthcheck
RUN chmod +x /usr/local/bin/fileserversync-healthcheck

HEALTHCHECK --interval=60s --timeout=10s --start-period=60s --retries=3 \
    CMD /usr/local/bin/fileserversync-healthcheck

ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/application.jar"]
