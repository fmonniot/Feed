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
- SQLite 3

## Installation

1. Clone the repository:
```bash
git clone https://github.com/yourusername/rss-aggregator.git
cd rss-aggregator
```

2. Build the project:
```bash
cargo build --release
```

3. Create the configuration file:
```bash
cp config.example.toml config.toml
```

4. Edit `config.toml` with your settings:
```toml
[server]
host = "127.0.0.1"
port = 3000

[auth]
username = "admin"
password = "your-secure-password"
jwt_secret = "change-this-to-a-random-secret-in-production"

[database]
url = "sqlite:rss_aggregator.db"
```

⚠️ **Security Note**: Keep your `config.toml` file secure! It contains plaintext credentials.

```bash
chmod 600 config.toml
echo "config.toml" >> .gitignore
```

## Usage

### Starting the Server

```bash
cargo run --release
```

Or run the compiled binary:
```bash
./target/release/rss-aggregator
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

### Server Settings

- `host` - IP address to bind to (default: `127.0.0.1`)
- `port` - Port number (default: `3000`)

### Authentication

- `username` - Single user username
- `password` - User password (stored in plaintext in config)
- `jwt_secret` - Secret key for JWT token signing (change in production!)

### Database

- `url` - SQLite database connection string (default: `sqlite:rss_aggregator.db`)

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
├── src/
│   └── main.rs           # Main application code
├── logs/                 # Log files (auto-created)
│   ├── rss_aggregator.log
│   ├── rss_aggregator.log.2026-01-01
│   └── ...
├── config.toml           # Configuration file (not in git)
├── rss_aggregator.db     # SQLite database (auto-created)
├── Cargo.toml
└── README.md
```

## Development

### Running in Development Mode

```bash
cargo run
```

### Running Tests

```bash
cargo test
```

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
WorkingDirectory=/path/to/rss-aggregator
ExecStart=/path/to/rss-aggregator/target/release/rss-aggregator
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
COPY . .
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y libsqlite3-0 && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/rss-aggregator /usr/local/bin/
COPY config.toml /app/config.toml
WORKDIR /app
EXPOSE 3000
CMD ["rss-aggregator"]
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