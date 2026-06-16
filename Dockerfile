# ── Stage 1: Build Kotlin/JS web app ──────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS web-builder

WORKDIR /repo
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY shared/ shared/
COPY web/ web/
# Stub out :app so Gradle's project-directory check passes without the Android SDK.
RUN mkdir -p app && touch app/build.gradle.kts
RUN ./gradlew :web:jsBrowserDistribution --no-daemon

# ── Stage 2: Build Rust server binary ─────────────────────────────────────────
FROM rust:1.91 AS rust-builder

WORKDIR /app

# FEED_VERSION is baked into the binary at compile time via option_env!().
# Pass it at build time: docker build --build-arg FEED_VERSION=$(./scripts/version.sh) .
ARG FEED_VERSION=0.0.0-dev

# Copy manifests first; this layer is cached as long as dependencies don't change.
COPY server/Cargo.toml server/Cargo.lock ./
RUN mkdir src && echo 'fn main() {}' > src/main.rs && \
    cargo build --release && \
    rm -f target/release/deps/server*

# Build the real binary (only this layer re-runs when source or FEED_VERSION changes).
COPY server/src ./src
RUN FEED_VERSION="${FEED_VERSION}" cargo build --release

# ── Stage 3: Runtime image ────────────────────────────────────────────────────
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        libsqlite3-0 \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 1001 feed && \
    useradd --uid 1001 --gid feed --shell /bin/sh --no-create-home feed

WORKDIR /app
COPY --from=rust-builder /app/target/release/server /usr/local/bin/server
COPY --from=web-builder /repo/web/build/dist/js/productionExecutable /app/web
RUN mkdir -p /app/data /app/logs && chown -R feed:feed /app

USER feed

VOLUME ["/app/data", "/app/logs"]
EXPOSE 3000
ENV RUST_LOG=info
CMD ["server"]
