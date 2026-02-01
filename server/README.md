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
POST /auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "your-secure-password"
}

# Response:
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "username": "admin"
}
```

Use the returned token in all subsequent requests:
```bash
Authorization: Bearer <token>
```

#### Feed Management

**Add a Feed Subscription**
```bash
POST /feeds
Authorization: Bearer <token>
Content-Type: application/json

{
  "url": "https://example.com/feed.xml"
}
```

**List All Feeds**
```bash
GET /feeds
Authorization: Bearer <token>

# Response:
[
  {
    "id": 1,
    "url": "https://example.com/feed.xml",
    "title": "Example Blog",
    "last_fetched": 1704556800,
    "fetch_interval_minutes": 30,
    "error_count": 0
  }
]
```

**Delete a Feed**
```bash
DELETE /feeds/:feed_id
Authorization: Bearer <token>
```

#### Articles

**Get Recent Articles**
```bash
GET /articles
Authorization: Bearer <token>

# Response:
[
  {
    "id": 1,
    "feed_id": 1,
    "guid": "https://example.com/post-123",
    "title": "Example Article",
    "content": "Article content...",
    "link": "https://example.com/post-123",
    "published": 1704556800
  }
]
```

**Get Articles from Specific Feed**
```bash
GET /feeds/:feed_id/articles
Authorization: Bearer <token>
```

#### Logs

**View Server Logs**
```bash
GET /logs?lines=100
Authorization: Bearer <token>

# Get last 500 lines
GET /logs?lines=500
Authorization: Bearer <token>
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
│   │   └── logging.rs    # Log management
│   ├── logs/             # Log files (auto-created)
│   │   ├── rss_aggregator.log
│   │   ├── rss_aggregator.log.2026-01-01
│   │   └── ...
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

```dockerfile
FROM rust:1.70 as builder
WORKDIR /app
COPY server/ .
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y libsqlite3-0 && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/server /usr/local/bin/
COPY server/config.toml /app/config.toml
WORKDIR /app
EXPOSE 3000
CMD ["server"]
```

Build and run:
```bash
docker build -t rss-aggregator .
docker run -d -p 3000:3000 -v $(pwd)/data:/app rss-aggregator
```

## Troubleshooting

### Feeds not updating

- Check the logs: `curl http://localhost:3000/logs -H "Authorization: Bearer <token>"`
- Verify feed URLs are valid and accessible
- Check `error_count` in the feeds list - high error counts indicate problematic feeds

### Authentication failures

- Ensure `config.toml` credentials match your login request
- Verify JWT token hasn't expired (30-day expiration)
- Check that the `jwt_secret` hasn't changed

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