# RSS Aggregator

A fast, lightweight server-side RSS feed aggregator built with Rust. Perfect for self-hosting your personal feed reader with automatic updates, authentication, and log management.

## Features

- 🚀 **Server-side feed fetching** - Automatically fetches and parses RSS/Atom feeds every 30 minutes
- 🔐 **JWT Authentication** - Secure single-user authentication with configurable credentials
- 💾 **SQLite Database** - Lightweight storage with automatic deduplication of articles
- 📊 **RESTful API** - Clean JSON API for managing feeds and retrieving articles
- 📝 **Log Management** - Daily log rotation with 7-day retention
- ⚡ **Concurrent Fetching** - Efficient async/await architecture for fast feed updates
- 🔄 **Error Handling** - Tracks and reports feed fetch failures

## Prerequisites

- Rust 1.70 or higher
- SQLite 3 (usually included with most systems)

## Installation

1. Clone the repository:
```bash
git clone https://github.com/yourusername/rss-aggregator.git
cd rss-aggregator/server
```

2. Build the project:
```bash
cargo build --release
```

3. Create the configuration file:
```bash
cp config.example.toml config.toml
```

4. Generate a secure password hash (recommended):
```bash
# The server will prompt you to create a config file on first run
./target/release/server
# Follow the interactive setup or edit config.toml manually
```

Or manually edit `config.toml` with your settings (see Configuration section below).

4. Generate a secure password hash (recommended):
```bash
# Generate Argon2id hash (requires argon2 CLI tool)
echo -n "your-password" | argon2 "$(openssl rand -base64 16)" -id -e

# Or use an online generator and copy the full hash string
```

5. Secure your configuration file:
```bash
chmod 600 config.toml
echo "config.toml" >> .gitignore
```

⚠️ **Security Note**: Never commit your `config.toml` to version control! It contains sensitive authentication data.

## Usage

### Starting the Server

```bash
cargo run --release
```

Or run the compiled binary:
```bash
./target/release/server
```

The server will start on the configured host and port (default: `http://127.0.0.1:3000`).

### API Endpoints

#### Authentication

**Login**
```bash
POST /v1/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "your-secure-password"
}

# Response: 200 OK
# Sets an httpOnly session cookie: session=<JWT>; SameSite=Strict; Path=/; Max-Age=604800
{
  "username": "admin"
}
```

All subsequent requests automatically include the cookie (browsers and Ktor's `HttpCookies` plugin handle this). The session is valid for 7 days; each successful request re-issues the cookie if fewer than 3.5 days remain (sliding window — active users never re-login).

**Logout**
```bash
POST /v1/auth/logout

# Response: 200 OK
# Clears the session cookie (Max-Age=0)
```

For `curl` testing, use a cookie jar:
```bash
curl -c cookies.txt -b cookies.txt -X POST http://localhost:3000/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"admin"}'

# Subsequent requests:
curl -b cookies.txt http://localhost:3000/v1/feeds
```

#### Feed Management

**Add a Feed Subscription**
```bash
POST /v1/feeds
Content-Type: application/json

{
  "url": "https://example.com/feed.xml"
}
```

**List All Feeds**
```bash
GET /v1/feeds
```

**Delete a Feed**
```bash
DELETE /v1/feeds/:feed_id
```

#### Articles

**Get Recent Articles**
```bash
GET /v1/articles
GET /v1/articles?is_read=false   # unread only
```

**Get Articles from Specific Feed**
```bash
GET /v1/feeds/:feed_id/articles
```

## Configuration

The RSS aggregator looks for `config.toml` in the following locations (in order):

1. **Standard OS configuration directory:**
   - Linux: `$HOME/.config/feed/config.toml`
   - macOS: `$HOME/Library/Application Support/eu.monniot.feed/config.toml`
   - Windows: `%APPDATA%\feed\config.toml`

2. **Local directory:** `./config.toml` (relative to where you run the server)

3. **Environment variable override:** Set `FEED_JWT_SECRET` to override the JWT secret

### Configuration File Structure

```toml
[server]
# Server bind address (default: "127.0.0.1")
# Use "0.0.0.0" to bind to all interfaces for remote access
host = "127.0.0.1"

# Server port (default: 3000)
port = 3000

[auth]
# Username for the single user account (required)
username = "admin"

# Password hash - Argon2id encoded (required)
# Generate this using the interactive setup or a password hashing tool
# Example: password_hash = "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$RqydZYmK5VhXJQJbYmQ1Wk"
password_hash = "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$RqydZYmK5VhXJQJbYmQ1Wk"

# Secret key for JWT token signing (required, min 32 characters recommended)
# Use a long, random string in production
jwt_secret = "change-this-to-a-random-secret-in-production-use-at-least-32-chars"
```

### Field Descriptions

#### `[server]` section
- **`host`** (string, required): IP address to bind the server to
  - `"127.0.0.1"` - Localhost only (default, most secure)
  - `"0.0.0.0"` - All interfaces (allows remote connections)
- **`port`** (integer, required): Port number for the server (default: `3000`)

#### `[auth]` section
- **`username`** (string, required): Username for the single user account
- **`password_hash`** (string, required): Argon2id hash of the user's password
  - **Never store plaintext passwords!**
  - Generate with: `echo -n "your-password" | argon2 "$(openssl rand -base64 16)" -id`
  - Use the interactive setup when running the server for the first time
- **`jwt_secret`** (string, required): Secret key for JWT token signing
  - **Minimum 32 characters recommended**
  - Use a cryptographically secure random string
  - Can be overridden with `FEED_JWT_SECRET` environment variable
  - Example: `openssl rand -base64 32`

### Security Best Practices

1. **Use strong passwords** - At least 12 characters with mixed case, numbers, and symbols
2. **Generate proper Argon2id hashes** - Use the interactive setup or proper hashing tools
3. **Keep JWT secret secure** - Use environment variables in production
4. **Restrict network access** - Use `"127.0.0.1"` unless you need remote access
5. **File permissions** - Set `chmod 600 config.toml` to restrict access
6. **Version control** - Never commit `config.toml` to git repositories

## Scheduled Tasks

The application runs two scheduled tasks:

1. **Feed Fetching** - Every 30 minutes
   - Fetches all subscribed feeds
   - Parses and stores new articles
   - Updates feed metadata

2. **Log Cleanup** - Daily at 2:00 AM
   - Removes log files older than 7 days
   - Keeps the `logs/` directory clean

## Directory Structure

```
rss-aggregator/
├── server/                # Main server application
│   ├── src/
│   │   ├── main.rs       # Main application code
│   │   ├── config.rs     # Configuration management
│   │   ├── db.rs         # Database operations
│   │   ├── fetcher.rs    # RSS feed fetching
│   │   ├── api.rs        # REST API handlers
│   │   ├── scheduler.rs  # Background task scheduling
│   │   ├── webhook.rs    # Webhook functionality
│   │   └── logging.rs    # stdout logging setup
│   ├── config.example.toml # Example configuration file
│   ├── Cargo.toml
│   └── README.md
├── config.toml           # Your configuration file (not in git)
└── README.md             # This file
```

## Development

### Running in Development Mode

```bash
cargo run
```

### Running Tests

```bash
cd server  # If you're in the root directory
cargo test
```

The test suite includes:
- Database operations and CRUD functionality
- Feed fetching and parsing
- HTTP conditional requests (ETag/Last-Modified)
- Error handling (network failures, timeouts)
- Mock server utilities

### Logging Levels

Set the `RUST_LOG` environment variable to control log verbosity:

```bash
# Debug level
RUST_LOG=debug cargo run

# Info level (default)
RUST_LOG=info cargo run

# Warn level only
RUST_LOG=warn cargo run
```

## Deployment

### Using systemd (Linux)

1. Create a systemd service file `/etc/systemd/system/rss-aggregator.service`:

```ini
[Unit]
Description=RSS Aggregator
After=network.target

[Service]
Type=simple
User=yourusername
WorkingDirectory=/path/to/rss-aggregator/server
ExecStart=/path/to/rss-aggregator/server/target/release/server
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

2. Enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable rss-aggregator
sudo systemctl start rss-aggregator
```

3. Check status:
```bash
sudo systemctl status rss-aggregator
```

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
  "feed-server:$VERSION"

# Verify
curl http://localhost:3000/v1/health
```

Volumes:
- `/app/config.toml` — required; mount a copy of `config.docker.example.toml` (edited).
- `/app/data` — persistent database (`feeds.db`). Use a named volume or a host directory.

The JWT secret in `config.toml` is overridden by `FEED_JWT_SECRET` at runtime, so secrets stay out of the config file.

## Observability

The server logs to **stdout** only. Capture, rotation, and retention are delegated to the
runtime — systemd/journald under the systemd deployment, or the container engine under Docker.
There is no in-app log file and no log endpoint; use the tools below.

### Reading logs

**systemd / journald** (the `feed.service` unit):

```bash
journalctl -u feed -f              # follow live
journalctl -u feed -p err          # errors and worse only
journalctl -u feed --since "1 hour ago"
journalctl -u feed --since today | grep "feed_id"
```

**Docker / Podman:**

```bash
docker logs -f feed                # follow live
docker logs --since 1h feed
```

Verbosity is controlled by `RUST_LOG` (default `info`) — see [Logging Levels](#logging-levels).

### Retention

journald manages retention globally; cap the unit's footprint if needed:

```bash
journalctl --vacuum-time=14d       # drop entries older than 14 days
journalctl --vacuum-size=200M      # cap on-disk journal size
```

Or set `SystemMaxUse=` in `journald.conf`. Under Docker, configure the daemon's
logging driver (e.g. `json-file` with `max-size`/`max-file`).

> **Coming next:** structured JSON logs (`LOG_FORMAT=json`) plus a `jq` query cookbook
> land with the structured-logging phase of ticket #74. Until then, logs are
> human-readable text — use `grep` as shown above.

## Troubleshooting

### Feeds not updating

- Check the logs via the runtime: `journalctl -u feed -f` (systemd) or `docker logs -f feed`
- Verify feed URLs are valid and accessible
- Check `error_count` in the feeds list - high error counts indicate problematic feeds

### Authentication failures

- Ensure `config.toml` credentials match your login request
- Session cookies expire after 7 days of inactivity (each request slides the window)
- Check that the `jwt_secret` hasn't changed between restarts (changing it invalidates all sessions)

### Database locked errors

- SQLite doesn't handle high concurrency well
- If experiencing issues, consider migrating to PostgreSQL (requires code changes)

## Performance

- **Feed fetching**: Concurrent, typically completes in seconds for dozens of feeds
- **API responses**: Sub-millisecond for cached data
- **Log queries**: 50-200ms depending on log file size
- **Memory usage**: ~10-50MB depending on number of feeds and articles

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Built with [Axum](https://github.com/tokio-rs/axum) web framework
- RSS parsing by [feed-rs](https://github.com/feed-rs/feed-rs)
- Database access via [SQLx](https://github.com/launchbadge/sqlx)
- Logging with [tracing](https://github.com/tokio-rs/tracing)

## Support

For issues, questions, or contributions, please open an issue on GitHub.