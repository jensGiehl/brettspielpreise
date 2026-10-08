FROM maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build
ARG TARGETARCH
RUN set -eu; \
    case "$TARGETARCH" in \
      amd64) jdk_arch=x64; jdk_sha=95fc37eb3a18a27a26d5904c2d89d52bace8dafa9a078ca27f4747fbc4bf070b ;; \
      arm64) jdk_arch=aarch64; jdk_sha=da4e9dde1fff90204739e969187bab4751bd59a2a1c479672e1a1810f7dd23ea ;; \
      *) exit 1 ;; \
    esac; \
    curl -fsSL "https://download.java.net/java/GA/jdk27/55ce5470a6294008af0057ff4626d0e5/35/GPL/openjdk-27_linux-${jdk_arch}_bin.tar.gz" -o /tmp/jdk.tar.gz; \
    echo "$jdk_sha  /tmp/jdk.tar.gz" | sha256sum -c -; \
    mkdir /opt/jdk27; tar -xzf /tmp/jdk.tar.gz -C /opt/jdk27 --strip-components=1; rm /tmp/jdk.tar.gz
ENV JAVA_HOME=/opt/jdk27
ENV PATH=/opt/jdk27/bin:$PATH
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn --strict-checksums -B -ntp clean verify

FROM mcr.microsoft.com/playwright/java:v1.63.0-noble@sha256:013e2595272806f887d91041fbf26be71dda2f48a805c717cc7de3bbca5339c8
LABEL org.opencontainers.image.source="https://github.com/jensGiehl/brettspielpreise"
LABEL org.opencontainers.image.description="Board game price API with Chromium and persistent H2 cache"
USER root
RUN apt-get update && apt-get install -y --no-install-recommends python3 curl ca-certificates xvfb x11-utils && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 bgprices && useradd --uid 10001 --gid 10001 --create-home bgprices && \
    mkdir -p /app/data /app/diagnostics /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix && chown -R 10001:10001 /app
COPY --from=build /opt/jdk27 /opt/jdk27
ENV JAVA_HOME=/opt/jdk27
ENV PATH=/opt/jdk27/bin:$PATH
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/bg-prices-1.0.0-SNAPSHOT.jar /app/bg-prices.jar
COPY --chown=10001:10001 scripts /app/scripts
RUN chmod +x /app/scripts/entrypoint.sh /app/scripts/with-display.sh
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD curl -fsS "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health/readiness" || exit 1
ENTRYPOINT ["/app/scripts/with-display.sh", "/app/scripts/entrypoint.sh"]
