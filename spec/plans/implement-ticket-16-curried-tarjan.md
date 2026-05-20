Date: 2026-05-19 18:33 PDT

# Ticket #16 — Real Dockerfile + image build

## Context

The server README shows an example Dockerfile (lines 338–353 of `server/README.md`) but it has several problems: it copies the git-ignored `config.toml`, runs as root, uses a stale Rust version (1.70), and ignores the web client assets the server can serve. No actual `Dockerfile` exists in the repository. This ticket wires up a real, working Docker image that includes both the Rust server binary and the compiled Kotlin/JS web app.

## Files to create

| File | Purpose |
|---|---|
| `Dockerfile` | 3-stage multi-stage build at repo root |
| `.dockerignore` | Exclude build artifacts and secrets from build context |
| `server/config.docker.example.toml` | Docker-specific config template (host=0.0.0.0, db/web paths wired to container paths) |
| `.github/workflows/docker.yml` | Dedicated CI workflow with GHA layer caching |

## Files to modify

| File | Change |
|---|---|
| `server/README.md` | Replace broken Docker example (lines 338–359) with build/run instructions |

---

## Implementation

### 1. `Dockerfile` (repo root)

The build requires both the Kotlin/JS web app (`shared/` + `web/`) and the Rust server (`server/`), so the build context is the full repo. Three stages:

```dockerfile
# ── Stage 1: Build Kotlin/JS web app ──────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS web-builder

WORKDIR /repo
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY shared/ shared/
COPY web/ web/
RUN ./gradlew :web:jsBrowserProductionWebpack --no-daemon

# ── Stage 2: Build Rust server binary ─────────────────────────────────────────
FROM rust:1.88 AS rust-builder

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
```

Key design notes:

- **Stage 1 (web-builder)**: Uses `eclipse-temurin:17-jdk` (JDK 17, matching CI in `android.yml`). The task `jsBrowserProductionWebpack` outputs to `web/build/dist/js/productionExecutable/` which is copied into the runtime image at `/app/web`.
- **Stage 2 (rust-builder)**: Uses `rust:1.88` matching `rust-version` in `server/Cargo.toml`. The dummy-`main` trick separates dependency compilation (slow, rarely changes) from source compilation (fast, frequently changes). `rm -f target/release/deps/server*` forces only the crate itself to recompile on source changes. `FEED_VERSION` is passed as a build arg and set as an env var during `cargo build` — `option_env!("FEED_VERSION")` in `server/src/api/handlers.rs` reads it at compile time to embed the version in the binary. Changing the version (new git tag/commit) only invalidates the final source build layer, not the deps layer.
- **Stage 3 (runtime)**: `debian:bookworm-slim` for a small image with a shell. `ca-certificates` is needed for outbound HTTPS (feed fetching). `libsqlite3-0` is the dynamic SQLite runtime library. UID/GID 1001 for the non-root `feed` user.
- `/app/config.toml` is **not** baked in — must be volume-mounted by the user.

### 2. `.dockerignore` (repo root)

```
.git/
.github/
.gradle/
build/
app/
server/target/
server/logs/
server/config.toml
server/test.db
web/build/
shared/build/
*/node_modules/
*.md
local.properties
```

Excludes the Android app (unused), build artifacts, secrets, and large directories that would otherwise bloat the build context sent to the Docker daemon.

### 3. `server/config.docker.example.toml`

```toml
# Feed server configuration for Docker.
# Copy to your host and mount at /app/config.toml:
#   -v /host/path/config.toml:/app/config.toml:ro

[server]
# Bind to all interfaces — required inside Docker.
host = "0.0.0.0"
port = 3000

[auth]
username = "admin"
# Hash for password "admin" — CHANGE THIS before deploying.
# See: https://argon2.online  (type=Argon2id, output=encoded)
password_hash = "$argon2id$v=19$m=65536,t=2,p=1$elZxeHB1VzhpcUliR3RkMA$pSockUc1J5m0mTLfKRb/mg"
# Override jwt_secret at runtime with: -e FEED_JWT_SECRET=your-secret
jwt_secret = "replace-with-a-random-secret-at-least-32-chars"

[database]
# Persisted to the /app/data volume.
url = "sqlite:///app/data/feeds.db"

[web]
# Bundled web client assets baked into the image.
assets_path = "/app/web"
```

Key differences from `config.example.toml`:
- `host = "0.0.0.0"` — required for Docker port mapping.
- `[database].url` pointing into `/app/data` (the declared VOLUME).
- `[web].assets_path = "/app/web"` — enables the bundled web client.

### 4. `.github/workflows/docker.yml`

A dedicated workflow separate from `rust.yml`. Uses `docker/build-push-action` with GitHub Actions layer caching (`cache-from: type=gha, cache-to: type=gha,mode=max`) to avoid rebuilding Rust dependencies on every run.

```yaml
name: Docker

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    name: Build Docker image
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0  # needed for git describe in version.sh

      - name: Derive version
        id: version
        run: echo "version=$(./scripts/version.sh)" >> $GITHUB_OUTPUT

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Build image
        uses: docker/build-push-action@v6
        with:
          context: .
          push: false
          build-args: FEED_VERSION=${{ steps.version.outputs.version }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
          tags: feed-server:${{ steps.version.outputs.version }}
```

**How the caching works**: `cache-from: type=gha` + `mode=max` stores intermediate layers from all three build stages in GitHub's Actions cache. Subsequent runs reuse cached layers:
- Rust deps layer: cached until `server/Cargo.lock` changes.
- Kotlin/JS layer: cached until any file in `shared/` or `web/` changes.
- A source-only change in `server/src/` or a version bump only re-runs the final `cargo build` layer (seconds, not minutes).

`fetch-depth: 0` is required so `git describe` in `scripts/version.sh` can see git tags. Without it, `git describe` fails and the version falls back to `0.0.0-dev`.

### 5. `server/README.md` — Docker section update

Replace lines 338–359 (the broken example block through the `docker run` command) with:

````markdown
### Using Docker

A `Dockerfile` at the repo root builds both the Rust server and the compiled web client:

```bash
# Build the image (version derived from nearest v* git tag)
VERSION=$(./scripts/version.sh)
docker build --build-arg FEED_VERSION="$VERSION" -t "feed-server:$VERSION" .

# Create a config from the Docker template
mkdir -p data
cp server/config.docker.example.toml data/config.toml
# Edit data/config.toml: set a real password_hash and a strong jwt_secret
# (or pass the secret via FEED_JWT_SECRET below)

# Run
docker run -d \
  --name feed \
  -p 3000:3000 \
  -v "$(pwd)/data/config.toml:/app/config.toml:ro" \
  -v feed_data:/app/data \
  -e FEED_JWT_SECRET="$(openssl rand -base64 32)" \
  feed-server

# Verify
curl http://localhost:3000/v1/health
```

Volumes:
- `/app/config.toml` — required; mount a copy of `config.docker.example.toml` (edited).
- `/app/data` — persistent database (`feeds.db`). Use a named volume or a host directory.
- `/app/logs` — optional; mount if you want logs to survive container restarts.

The JWT secret in `config.toml` is overridden by `FEED_JWT_SECRET` at runtime, so secrets stay out of the config file.
````

---

## Verification

No automated Rust unit test can cover Dockerfile correctness, so validation is:

1. **CI gate** — the new `docker.yml` workflow runs `docker build .` on every push/PR; a broken Dockerfile fails the build.
2. **Manual smoke test** (run after implementation):
   ```bash
   docker build -t feed-server .
   # Should complete in <10 min first run; cached layers on subsequent runs.
   docker images feed-server   # Check size (expect ~100–150 MB runtime, not ~2 GB)
   docker run --rm feed-server whoami   # Must print "feed", not "root"
   # Start with config mounted, hit health endpoint:
   docker run -d -p 3000:3000 \
     -v "$(pwd)/server/config.docker.example.toml:/app/config.toml:ro" \
     -e FEED_JWT_SECRET=test-secret-long-enough-for-the-server \
     feed-server
   curl http://localhost:3000/v1/health   # Must return 200
   ```
3. **DB persistence**: stop the container, restart it with the same data volume, confirm articles survive.

Infrastructure-only changes (Dockerfile, .dockerignore, config template, README updates) do not require new Rust unit tests; the CI build job serves as the automated gate per the testing requirement exception for "things that can't be unit-tested".
