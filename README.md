# Feed

A self-hosted RSS reader with a Rust backend and an Android client.

## Architecture

- **Server** (`server/`) — Async Rust service built with Axum, backed by SQLite. Handles feed fetching, article storage, full-text search, and JWT authentication. See [`server/README.md`](server/README.md) for setup and configuration.
- **Android app** (`app/`) — Kotlin/Jetpack Compose client using Material 3, Retrofit, and Room for local caching. Gradle project is configured at the top level.

## Building

### Server

```sh
cd server
cargo build --release
```

### Android app

```sh
./gradlew assembleDebug
```

## Running

Start the server first (see [`server/README.md`](server/README.md) for configuration), then point the Android app at its URL.


## Notes

Dependency Injection project: https://zacsweers.github.io/metro/latest/quickstart/
Not sure if the project is going to be big enough to justify it, but it's KMP compatible.
