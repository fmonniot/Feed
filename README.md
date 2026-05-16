# Feed

A self-hosted, single-user RSS reader. The server fetches and stores feeds; clients read and manage them.

## Architecture

```
Feed/
├── server/      Rust + Axum — feed fetching, SQLite, REST API, session cookies
├── shared/      Kotlin Multiplatform library — data models, Ktor HTTP client,
│                FeedViewModel, FeedRepository interface (targets: Android + JS + wasmJs)
├── app/         Android application — Jetpack Compose UI, Room local cache
└── web/         Kotlin/JS web application — plain DOM UI, in-memory state
```

**Server** handles everything stateful: periodic feed fetching, article deduplication, full-text search, and authentication via `httpOnly` session cookies (7-day sliding-window JWT).

**`shared/`** is the Kotlin Multiplatform data layer consumed by both clients. It owns the Ktor-based API wrappers, all serializable data models, `FeedViewModel`, `FeedRepository` interface, and platform-specific HTTP client factories (Android uses `ktor-client-android` + DataStore cookie storage; JS/wasmJs use `ktor-client-js` and let the browser handle `SameSite=Strict` cookies natively).

**`app/`** is a pure Android module. It implements `FeedRepository` with Room for offline caching, wraps the shared `FeedViewModel` in an Android lifecycle-aware `ViewModel`, and renders the UI with Jetpack Compose.

**`web/`** is a Kotlin/JS application. It implements `FeedRepository` in-memory (no persistence), uses plain DOM APIs for the UI (so native text selection, find-in-page, and right-click context menus all work), and is served by the Rust server at the same origin as the API.

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, development workflow, and module-by-module guidance.

## Quick start

### 1. Server

```sh
cd server
cp config.example.toml config.toml  # edit username, password_hash, jwt_secret
cargo run
```

The server listens on `http://127.0.0.1:3000` by default. Full setup is in [server/README.md](server/README.md).

### 2. Android app

Point the app at the server URL (Settings → Server URL or the "Server:" button on the login screen), then log in. The default URL assumes Android emulator loopback (`http://10.0.2.2:3000/`).

```sh
./gradlew :app:installDebug
```

### 3. Web client

Build the JS bundle and drop it next to the server binary:

```sh
./gradlew :web:jsBrowserProductionWebpack
cp -r web/build/dist/js/productionExecutable/* /path/to/server/web/
```

Add to `config.toml`:

```toml
[web]
assets_path = "./web"
```

Then open `http://127.0.0.1:3000/` in a browser. Or for development, use webpack's dev server pointed at the running Rust server (see [CONTRIBUTING.md](CONTRIBUTING.md)).

## Features

- Periodic RSS/Atom feed fetching (every 30 minutes by default)
- Article deduplication, read/starred state, full-text search
- Feed management: custom titles, fetch intervals, pause/resume, delete
- Category support (server-side; Android UI in progress — see [TODO.md](TODO.md) #4)
- OPML import
- Webhook notifications on new articles
- Statistics dashboard endpoint
- Session cookies: 7-day sliding window, no manual refresh needed

## API

Full REST API reference: [server/API_DOCUMENTATION.md](server/API_DOCUMENTATION.md).

Authentication: `POST /v1/auth/login` with `{username, password}` sets an `httpOnly` session cookie. All subsequent requests send the cookie automatically. `POST /v1/auth/logout` clears it.

## Backlog

Active tickets: [TODO.md](TODO.md).
