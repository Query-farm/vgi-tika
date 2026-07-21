# Copyright 2026 Query Farm LLC - https://query.farm
#
# Single image that serves the network transports of the `tika` VGI worker:
#   docker run ... IMG            -> HTTP server on $PORT      (default; Fly.io / local)
#   docker run -i ... IMG stdio   -> stdio worker DuckDB spawns on-host
#   docker run ... IMG unix <sock>-> AF_UNIX launcher on <sock>
# See docker-entrypoint.sh.
#
# vgi-tika is STATELESS: extraction is pure compute over the document bytes /
# path handed to each call, so there is no /data volume, no model registry, and
# no `farm.query.vgi.volumes` mount-discovery label. The image is the shaded fat
# JAR (Tika 3.x + PDFBox + POI) + a tiny entrypoint, plus the system Tesseract
# binary so the `ocr` function and scanned-PDF auto-OCR actually work (Tika
# guards to NULL when Tesseract is absent).
# syntax=docker/dockerfile:1

# ---- build stage -----------------------------------------------------------
# JDK image builds the shaded fat JAR via the Gradle wrapper. The VGI Java SDK
# (farm.query:vgi / farm.query:vgirpc) resolves from Maven Central, so the build
# needs only network to Central — no sibling checkout, no composite build. The
# Gradle caches (~/.gradle) are a BuildKit cache mount, so the assembled JAR is
# copied OUT to a fixed non-cache path before the layer ends.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Gradle wrapper + build scripts first (better layer caching on source-only edits).
COPY gradlew ./
COPY gradle ./gradle
COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY src ./src

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain shadowJar \
    && cp build/libs/vgi-tika-*-all.jar /worker.jar

# ---- runtime stage ---------------------------------------------------------
# eclipse-temurin JRE (jammy) so the HEALTHCHECK below has a real `curl` and apt.
# The JRE + the fat JAR + Tesseract are needed at runtime.
FROM eclipse-temurin:21-jre-jammy

# Build metadata, wired from docker/metadata-action outputs in CI.
ARG VERSION=0.0.0
ARG GIT_COMMIT=unknown
ARG SOURCE_URL=https://github.com/Query-farm/vgi-tika

# Standard OCI labels + the VGI transport-advertisement label. `transports` lists
# the NETWORK transports this image serves on a port (http). stdio is a spawn
# mode and unix is a local socket, so neither is a port-exposed network transport.
LABEL org.opencontainers.image.title="vgi-tika" \
      org.opencontainers.image.description="Extract text, metadata, language & OCR from PDF/DOCX/PPTX/XLSX/HTML/email/images with Apache Tika as a VGI worker for DuckDB/SQL (stdio + HTTP + AF_UNIX)" \
      org.opencontainers.image.source="${SOURCE_URL}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.licenses="MIT" \
      farm.query.vgi.transports='["http"]'

ENV PORT=8000 \
    # Build provenance surfaced by Main.GIT_COMMIT (implementation_version).
    VGI_TIKA_GIT_COMMIT=${GIT_COMMIT} \
    # Arrow's off-heap MemoryUtil needs java.nio opened (the fat-JAR manifest also
    # bakes Add-Opens); silence the JDK 21 native-access warning for cleanliness.
    # These print to stderr, so the stdio Arrow-IPC transport's stdout stays clean.
    JAVA_TOOL_OPTIONS="--add-opens=java.base/java.nio=ALL-UNNAMED --enable-native-access=ALL-UNNAMED"

WORKDIR /app

# curl backs the HEALTHCHECK below; tesseract-ocr powers the `ocr` scalar and
# scanned-PDF auto-OCR (without it Tika returns NULL rather than failing — but a
# document-extraction image should ship the real capability).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tesseract-ocr \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /worker.jar /app/worker.jar
COPY --chmod=0755 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

# Run unprivileged. No state, no volume — there is nothing to own or persist.
RUN useradd --create-home --uid 10001 app
USER app

EXPOSE 8000

# Readiness probe for HTTP mode. Inert for a short-lived stdio container, which
# has no HTTP server (the probe just fails harmlessly there).
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://localhost:${PORT:-8000}/health" || exit 1

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["http"]
