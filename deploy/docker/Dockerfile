# Container image of an example host (a Spring Boot service that embeds the engines).
# Build context: the repository root.
#
#   docker build -f deploy/docker/Dockerfile -t horus-bpm/rule-engine-only:local .
#   docker build -f deploy/docker/Dockerfile --build-arg EXAMPLE=rule-and-process -t horus-bpm/rule-and-process:local .
#
# The runtime image is linux/amd64 or linux/arm64, the architecture of the machine that builds it unless
# --platform says otherwise (see stage 3). EXAMPLE names a folder under examples/. Base images are pinned by digest; the comment names the
# version the digest resolved to. Update tag, digest and comment together (docs/DEPLOYING.md).
# Nothing secret is read at build time: credentials reach the container as environment variables at run time.

ARG EXAMPLE=rule-engine-only

# Stage 1: the cockpit bundle, only when the checkout has a cockpit web application.
# Node 22.23.5 on Alpine. With tools/release/build-cockpit.sh (the release build's script) the bundle is
# built exactly as a release builds it: npm ci --ignore-scripts, lockfile-lint, registry signatures,
# licence check, pinned Node version. Without it, npm ci --ignore-scripts and the cockpit's own build.
# No npm cache mount: npm ci checks every tarball against the lockfile's integrity hash.
FROM --platform=$BUILDPLATFORM node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS cockpit
WORKDIR /src
COPY . .
RUN set -eu; \
    mkdir -p /out/dist /out/meta; \
    if [ ! -f cockpit/web/package-lock.json ]; then \
      echo "No cockpit/web in this checkout; the image is built without the cockpit."; \
    elif [ -f tools/release/build-cockpit.sh ]; then \
      sh tools/release/build-cockpit.sh /out/meta && cp -R cockpit/web/dist/. /out/dist/; \
    else \
      cd cockpit/web && npm ci --ignore-scripts --no-audit --no-fund && npm run build && cp -R dist/. /out/dist/; \
    fi

# Stage 2a: the POMs only, each in its place. The dependency layer of stage 2 copies them, so that
# layer is keyed on the POMs' content alone and a commit that changes no POM reuses it (BuildKit
# compares content, not timestamps).
FROM --platform=$BUILDPLATFORM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS poms
WORKDIR /src
COPY . .
RUN set -eu; \
    mkdir -p /poms; \
    find . -name pom.xml -not -path '*/node_modules/*' -not -path '*/target/*' | tar -cf - -T - | tar -xf - -C /poms

# Stage 2: build the example host and the engine modules it needs.
# Maven 3.9.16 (the line CI uses), Eclipse Temurin JDK 21.
# Maven runs through tools/mvn-retry.sh, as in CI: a run that failed only because Maven Central did not
# deliver an artifact (a false 404, or a 429 after the resolver's own retries in .mvn/maven.config) runs
# again with -U, at most 3 attempts, after MVN_RETRY_WAIT then twice that many seconds. Any other failure
# ends the build at once. Retries are plain "mvn-retry: warning: ... Retrying" lines in the build log.
FROM --platform=$BUILDPLATFORM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
ARG EXAMPLE
ARG MVN_RETRY_WAIT=60
WORKDIR /src
# Dependency layer: the real package lifecycle of the example host and the modules it needs, run on the
# POMs alone, puts every dependency and plugin the build uses into /root/.m2/repository in this layer.
# With no sources the compiler skips its annotation processor, so that one is fetched by name. The help
# and dependency plugins used here are pinned in the parent POM, and the enforcer checks they stay pinned.
# The layer changes only when a POM, .mvn/ or the licence files (checked by the enforcer) change.
COPY --from=poms /poms/ ./
COPY .mvn/ .mvn/
COPY LICENSE COMMERCIAL-LICENSE.md ./
COPY tools/mvn-retry.sh tools/mvn-retry.sh
RUN set -eu; \
    test -f "examples/${EXAMPLE}/pom.xml" || { echo "examples/${EXAMPLE} is not an example host" >&2; exit 1; }; \
    bash tools/mvn-retry.sh -B -ntp -pl "examples/${EXAMPLE}" -am package -DskipTests \
      -Dcockpit.bundle.skip=true -Dspring-boot.repackage.skip=true; \
    boot="$(mvn -B -ntp -q -N help:evaluate -Dexpression=spring-boot.version -DforceStdout)"; \
    bash tools/mvn-retry.sh -B -ntp -q -N dependency:get -Dartifact="org.springframework.boot:spring-boot-configuration-processor:${boot}"; \
    find . -name target -type d -prune -exec rm -rf {} +
COPY . .
COPY --from=cockpit /out/dist/ /tmp/cockpit-dist/
# Online, as before, so every plugin runs exactly as in CI (CycloneDX skips itself offline); with the
# dependency layer in place Maven fetches only what that layer lacks. The last line counts those downloads.
RUN set -eu; \
    skip_cockpit=true; \
    if [ -f /tmp/cockpit-dist/index.html ]; then \
      rm -rf cockpit/web/dist && mkdir -p cockpit/web/dist && cp -R /tmp/cockpit-dist/. cockpit/web/dist/; \
      skip_cockpit=false; \
    fi; \
    bash tools/mvn-retry.sh -B -pl "examples/${EXAMPLE}" -am package -DskipTests -Dcockpit.bundle.skip="${skip_cockpit}" \
      > /tmp/build.log 2>&1 || { grep -v '^\[INFO\] Download' /tmp/build.log; exit 1; }; \
    grep -v '^\[INFO\] Download' /tmp/build.log; \
    echo "Maven downloads in this step, beyond the dependency layer: $(grep -c '^\[INFO\] Downloaded from' /tmp/build.log || true)"; \
    grep '^\[INFO\] Downloaded from' /tmp/build.log | sed 's/^/  /' || true; \
    jar="$(find "examples/${EXAMPLE}/target" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    test -n "$jar"; \
    mkdir -p /layers; \
    java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination /layers

# Stage 3: the Temurin JRE 21.0.13.1+2 from the Adoptium release, verified twice before use:
#   1. its detached GPG signature against the Adoptium key committed as deploy/docker/adoptium.asc,
#      whose primary key fingerprint must be 3B04D753C9050D9A5D343F39843C48A565F8F04B (provenance);
#   2. the SHA-256 pinned below (the bytes are the ones reviewed when the version was bumped).
# This stage is thrown away: gnupg never reaches the runtime image.
# 21.0.13.1 fixes CVE-2026-114938 (high); the official eclipse-temurin images still carry 21.0.13+9.
# One pinned SHA-256 per architecture, both from the .sha256.txt beside each archive on the release page;
# the GPG check applies to both. Update both together.
FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS jre
ARG TARGETARCH
ARG JRE_RELEASE=jdk-21.0.13.1+2
ARG JRE_FILE_VERSION=21.0.13.1_2
ARG ADOPTIUM_KEY_FINGERPRINT=3B04D753C9050D9A5D343F39843C48A565F8F04B
COPY deploy/docker/adoptium.asc /tmp/adoptium.asc
RUN set -eu; \
    case "${TARGETARCH}" in \
      amd64) arch=x64; sha256=72835f41a468ae949fc058f8ec6a73d303ca3e5ea165621e08aacb36bb537bba ;; \
      arm64) arch=aarch64; sha256=0236751150c82f1ef91be8cc32a36091a95e97650285fea848f2bb82311e5da7 ;; \
      *) echo "No pinned Temurin JRE for ${TARGETARCH}; build for linux/amd64 or linux/arm64" >&2; exit 1 ;; \
    esac; \
    apk add --no-cache gnupg; \
    url="https://github.com/adoptium/temurin21-binaries/releases/download/$(echo "${JRE_RELEASE}" | sed 's/+/%2B/')/OpenJDK21U-jre_${arch}_alpine-linux_hotspot_${JRE_FILE_VERSION}.tar.gz"; \
    wget -q -O /tmp/jre.tar.gz "$url"; \
    wget -q -O /tmp/jre.tar.gz.sig "$url.sig"; \
    export GNUPGHOME="$(mktemp -d)"; \
    gpg --batch --quiet --import /tmp/adoptium.asc; \
    gpg --batch --with-colons --fingerprint | grep -q "^fpr:::::::::${ADOPTIUM_KEY_FINGERPRINT}:\$" \
      || { echo "deploy/docker/adoptium.asc is not the Adoptium key ${ADOPTIUM_KEY_FINGERPRINT}" >&2; exit 1; }; \
    gpg --batch --status-fd 1 --verify /tmp/jre.tar.gz.sig /tmp/jre.tar.gz | grep -q "^\[GNUPG:\] VALIDSIG ${ADOPTIUM_KEY_FINGERPRINT} " \
      || { echo "The JRE archive is not signed by the Adoptium key ${ADOPTIUM_KEY_FINGERPRINT}" >&2; exit 1; }; \
    echo "GPG signature OK: signed by ${ADOPTIUM_KEY_FINGERPRINT}"; \
    gpgconf --kill all; rm -rf "$GNUPGHOME"; \
    echo "${sha256}  /tmp/jre.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/java/openjdk; \
    tar -xzf /tmp/jre.tar.gz -C /opt/java/openjdk --strip-components=1

# Stage 4: the runtime file system. Alpine 3.24 with only musl and busybox, plus the verified JRE.
FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS runtime-root
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
RUN set -eu; \
    addgroup -S -g 10001 horus; \
    adduser -S -D -H -u 10001 -G horus -s /sbin/nologin horus; \
    mkdir -p /app && chown 10001:10001 /app; \
    # The package manager and its TLS and compression libraries are not needed at run time.
    apk --purge del apk-tools ssl_client; \
    rm -rf /var/cache/apk /etc/apk/cache

# Stage 5: the runtime image, one base layer from stage 4 (nothing deleted hides in a lower layer),
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
