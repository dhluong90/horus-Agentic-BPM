# Container image of an example host (a Spring Boot service that embeds the engines).
# Build context: the repository root.
#
#   docker build --platform linux/amd64 -f deploy/docker/Dockerfile -t horus-bpm/rule-engine-only:local .
#   docker build --platform linux/amd64 -f deploy/docker/Dockerfile --build-arg EXAMPLE=rule-and-process -t horus-bpm/rule-and-process:local .
#
# The runtime image is linux/amd64 (see stage 3). EXAMPLE names a folder under examples/. Base images are pinned by digest; the comment names the
# version the digest resolved to. Update tag, digest and comment together (docs/DEPLOYING.md).
# Nothing secret is read at build time: credentials reach the container as environment variables at run time.

ARG EXAMPLE=rule-engine-only

# Stage 1: the cockpit bundle, only when the checkout has a cockpit web application.
# Node 22.23.5 on Alpine.
FROM --platform=$BUILDPLATFORM node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS cockpit
WORKDIR /src
COPY . .
RUN --mount=type=cache,id=horus-bpm-npm,target=/root/.npm \
    mkdir -p /out \
    && if [ -f cockpit/web/package-lock.json ]; then \
         cd cockpit/web && npm ci --no-audit --no-fund && npm run build && cp -R dist/. /out/; \
       else \
         echo "No cockpit/web in this checkout; the image is built without the cockpit."; \
       fi

# Stage 2: build the example host and the engine modules it needs.
# Maven 3.9.16 (the line CI uses), Eclipse Temurin JDK 21.
FROM --platform=$BUILDPLATFORM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
ARG EXAMPLE
WORKDIR /src
COPY . .
COPY --from=cockpit /out/ /tmp/cockpit-dist/
RUN --mount=type=cache,id=horus-bpm-m2,target=/root/.m2/repository \
    set -eu; \
    test -f "examples/${EXAMPLE}/pom.xml" || { echo "examples/${EXAMPLE} is not an example host" >&2; exit 1; }; \
    skip_cockpit=true; \
    if [ -f /tmp/cockpit-dist/index.html ]; then \
      rm -rf cockpit/web/dist && mkdir -p cockpit/web/dist && cp -R /tmp/cockpit-dist/. cockpit/web/dist/; \
      skip_cockpit=false; \
    fi; \
    mvn -B -ntp -pl "examples/${EXAMPLE}" -am package -DskipTests -Dcockpit.bundle.skip="${skip_cockpit}"; \
    jar="$(find "examples/${EXAMPLE}/target" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    test -n "$jar"; \
    mkdir -p /layers; \
    java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination /layers

# Stage 3: the runtime file system. Alpine 3.24 with only musl and busybox, plus the Temurin JRE
# 21.0.13.1+2, downloaded from the Adoptium release and checked against its published SHA-256.
# 21.0.13.1 fixes CVE-2026-114938 (high); the official eclipse-temurin images still carry 21.0.13+9.
# Adoptium publishes this JRE for x64 only so far, so the image is linux/amd64 (docker-compose.yml sets
# the platform). Add the aarch64 checksum here when Adoptium publishes that build.
FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS runtime-root
ARG TARGETARCH
ARG JRE_RELEASE=jdk-21.0.13.1+2
ARG JRE_FILE_VERSION=21.0.13.1_2
RUN set -eu; \
    case "${TARGETARCH}" in \
      amd64) arch=x64; sha256=72835f41a468ae949fc058f8ec6a73d303ca3e5ea165621e08aacb36bb537bba ;; \
      *) echo "No patched Temurin JRE for ${TARGETARCH} yet; build with --platform linux/amd64" >&2; exit 1 ;; \
    esac; \
    url="https://github.com/adoptium/temurin21-binaries/releases/download/$(echo "${JRE_RELEASE}" | sed 's/+/%2B/')/OpenJDK21U-jre_${arch}_alpine-linux_hotspot_${JRE_FILE_VERSION}.tar.gz"; \
    wget -q -O /tmp/jre.tar.gz "$url"; \
    echo "${sha256}  /tmp/jre.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/java/openjdk; \
    tar -xzf /tmp/jre.tar.gz -C /opt/java/openjdk --strip-components=1; \
    rm -f /tmp/jre.tar.gz; \
    addgroup -S -g 10001 horus; \
    adduser -S -D -H -u 10001 -G horus -s /sbin/nologin horus; \
    mkdir -p /app && chown 10001:10001 /app; \
    # The package manager and its TLS and compression libraries are not needed at run time.
    apk --purge del apk-tools ssl_client alpine-keys; \
    rm -rf /var/cache/apk /etc/apk/cache

# Stage 4: the runtime image, one base layer from stage 3 (nothing deleted hides in a lower layer),
# then the application layers. JRE only, no build tools, non-root.
FROM scratch
COPY --from=runtime-root / /
ENV PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    JAVA_HOME=/opt/java/openjdk \
    LANG=C.UTF-8
ARG EXAMPLE
LABEL org.opencontainers.image.title="HORUS Agentic BPM example host: ${EXAMPLE}" \
      org.opencontainers.image.source="https://github.com/dhluong90/horus-Agentic-BPM" \
      org.opencontainers.image.licenses="PolyForm-Noncommercial-1.0.0 OR LicenseRef-HORUS-Commercial"

WORKDIR /app

# One layer per Spring Boot layer, least to most frequently changed.
COPY --from=build --chown=10001:10001 /layers/dependencies/ ./
COPY --from=build --chown=10001:10001 /layers/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /layers/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /layers/application/ ./

USER 10001:10001
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

# Health is public on every example host; busybox wget is kept in the runtime image for this check.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -q -T 2 -O /dev/null "http://127.0.0.1:${PORT}/actuator/health" || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
