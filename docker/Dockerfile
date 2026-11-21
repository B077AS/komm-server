# komm-server as a container: a Java 21 runtime (Azul Zulu) and the server jar. PostgreSQL and the LiveKit media server are inside the
# jar and start from it, exactly as in a normal install, so there is nothing else to run or keep in sync.
#
# The jar is built outside (the release workflow builds it once, pointed at the production hub), this only wraps it:
#   ./mvnw -B clean package -DskipTests
#   docker build -f docker/Dockerfile -t komm-server .                       # picks up target/komm-server-*.jar
#   docker build -f docker/Dockerfile --build-arg JAR=path/to/komm.jar -t komm-server .
#
# linux/amd64 only: the jar ships the amd64 LiveKit binary.
FROM azul/zulu-openjdk:21-jre

ARG JAR=target/komm-server-*.jar
ARG VERSION=dev

LABEL org.opencontainers.image.title="komm-server" \
      org.opencontainers.image.description="Self-hosted Komm community server" \
      org.opencontainers.image.source="https://github.com/B077AS/komm-server" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="${VERSION}"

# tini reaps the processes the server leaves behind (PostgreSQL's workers, LiveKit) and passes SIGTERM on to the JVM,
# whose shutdown hooks stop both cleanly.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tini \
 && rm -rf /var/lib/apt/lists/*

# Not root: the embedded PostgreSQL refuses to start as root.
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin komm \
 && mkdir /data \
 && chown komm:komm /data

COPY ${JAR} /app/komm-server.jar

USER komm
WORKDIR /data
# Everything the server writes (database, keys, uploads, logs, the unpacked LiveKit and PostgreSQL programs).
ENV KOMM_DATA_DIR=/data
VOLUME /data

# Run with `network_mode: host` (see docker-compose.yml): the ports are chosen in the Komm client and applied after the
# first start, and LiveKit has to advertise the machine's real addresses to clients. Informational only.
EXPOSE 8090 7881/tcp 7882/udp

# Healthy once the server is up and activated (it writes /data/ready then, and removes it on shutdown). A server that
# was given no valid verification code serves nothing and waits for a new one, so it never becomes healthy: `docker ps`
# shows "unhealthy" after the start period instead of a container that is "Up" but does nothing.
HEALTHCHECK --interval=30s --timeout=3s --start-period=3m --retries=3 CMD test -f /data/ready

ENTRYPOINT ["tini", "--", "java", "-jar", "/app/komm-server.jar"]
