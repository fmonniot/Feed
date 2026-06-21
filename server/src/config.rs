//! Configuration types and loading for the RSS aggregator server.

use argon2::password_hash::PasswordHashString;
use directories::ProjectDirs;
use serde::Deserialize;
use std::str::FromStr;

/// Main configuration structure.
#[derive(Debug, Deserialize, Clone)]
pub struct Config {
    pub server: ServerConfig,
    pub auth: AuthConfig,
    pub database: Option<DatabaseConfig>,
    pub web: Option<WebConfig>,
    /// Server-side fetch cadence and good-citizen settings. Optional; when the
    /// `[fetch]` section is absent every field falls back to its built-in default.
    #[serde(default)]
    pub fetch: FetchConfig,
    /// Article retention settings. Optional; when the `[retention]` section is
    /// absent every field falls back to its built-in default.
    #[serde(default)]
    pub retention: RetentionConfig,
}

/// Web client static-file serving configuration.
#[derive(Debug, Deserialize, Clone)]
pub struct WebConfig {
    /// Directory containing the compiled Wasm bundle (index.html + feed-web.js + .wasm).
    pub assets_path: String,
}

/// Server configuration (host, port).
#[derive(Debug, Deserialize, Clone, serde::Serialize)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
}

/// Authentication configuration.
#[derive(Debug, Deserialize, Clone)]
pub struct AuthConfig {
    pub username: String,
    /// Argon2 encoded password hash.
    #[serde(deserialize_with = "deser_from_str")]
    pub password_hash: PasswordHashString,
    pub jwt_secret: String,
}

/// Database configuration.
#[derive(Debug, Deserialize, Clone)]
pub struct DatabaseConfig {
    pub url: Option<String>,
}

/// Server fetch cadence and good-citizen configuration (`[fetch]` section).
///
/// These values are the **config-file tier** of the settings fallback chain
/// (persisted KV → config → built-in default; see [`crate::settings`]). A
/// persisted KV value overrides the config value; an absent config field falls
/// back to the built-in default constant in [`crate::settings::defaults`].
#[derive(Debug, Deserialize, Clone)]
pub struct FetchConfig {
    /// How often the scheduler loop wakes (minutes). Per-feed interval still gates
    /// individual fetches; this only bounds how finely intervals can be honored.
    #[serde(default = "FetchConfig::default_scheduler_tick_minutes")]
    pub scheduler_tick_minutes: i64,
    /// Default fetch interval (minutes) inherited by newly added feeds.
    #[serde(default = "FetchConfig::default_default_interval_minutes")]
    pub default_interval_minutes: i64,
    /// Hard floor (minutes) on any feed's fetch interval; protects upstreams from
    /// a client or config typo asking for an aggressive cadence.
    #[serde(default = "FetchConfig::default_min_interval_minutes")]
    pub min_interval_minutes: i64,
    /// Contact URL embedded in the outgoing User-Agent so site operators can reach
    /// the operator. The version is baked in at build time, not configured here.
    #[serde(default = "FetchConfig::default_contact_url")]
    pub contact_url: String,
    /// Whether to honor upstream `Retry-After` headers on 429/503 responses.
    #[serde(default = "FetchConfig::default_respect_retry_after")]
    pub respect_retry_after: bool,
    /// Enable adaptive fetch intervals (§3.3.4). When `true`, feeds that return
    /// many consecutive 304 Not Modified responses get their effective interval
    /// lengthened (bounded by `max_interval_minutes`), and feeds that change on
    /// every fetch get their effective interval shortened (bounded by
    /// `min_interval_minutes`). When `false` (default), behavior is unchanged —
    /// every feed uses its configured interval exactly.
    #[serde(default = "FetchConfig::default_adaptive_interval")]
    pub adaptive_interval: bool,
    /// Hard ceiling (minutes) on the adaptive effective interval. Only used when
    /// `adaptive_interval = true`. (default: 1440 = 24 hours)
    #[serde(default = "FetchConfig::default_max_interval_minutes")]
    pub max_interval_minutes: i64,
}

impl FetchConfig {
    fn default_scheduler_tick_minutes() -> i64 {
        crate::settings::defaults::SCHEDULER_TICK_MINUTES
    }
    fn default_default_interval_minutes() -> i64 {
        crate::settings::defaults::DEFAULT_FETCH_INTERVAL_MINUTES
    }
    fn default_min_interval_minutes() -> i64 {
        crate::settings::defaults::MIN_FETCH_INTERVAL_MINUTES
    }
    fn default_contact_url() -> String {
        crate::settings::defaults::CONTACT_URL.to_string()
    }
    fn default_respect_retry_after() -> bool {
        crate::settings::defaults::RESPECT_RETRY_AFTER
    }
    fn default_adaptive_interval() -> bool {
        crate::settings::defaults::ADAPTIVE_INTERVAL
    }
    fn default_max_interval_minutes() -> i64 {
        crate::settings::defaults::MAX_INTERVAL_MINUTES
    }
}

impl Default for FetchConfig {
    fn default() -> Self {
        FetchConfig {
            scheduler_tick_minutes: Self::default_scheduler_tick_minutes(),
            default_interval_minutes: Self::default_default_interval_minutes(),
            min_interval_minutes: Self::default_min_interval_minutes(),
            contact_url: Self::default_contact_url(),
            respect_retry_after: Self::default_respect_retry_after(),
            adaptive_interval: Self::default_adaptive_interval(),
            max_interval_minutes: Self::default_max_interval_minutes(),
        }
    }
}

/// Article retention configuration (`[retention]` section).
///
/// Config-file tier of the settings fallback chain. The persisted KV keys
/// `retention_days` / `retention_purge_read_only` override these values; absent
/// config fields fall back to the built-in defaults in
/// [`crate::settings::defaults`].
#[derive(Debug, Deserialize, Clone)]
pub struct RetentionConfig {
    /// How long articles are kept (days) before the daily sweep purges them.
    /// `null`/absent means the built-in default applies via the fallback chain.
    #[serde(default = "RetentionConfig::default_days")]
    pub days: i64,
    /// When `true` (default) the sweep only deletes read articles, keeping unread
    /// as a durable TODO list. `false` enables a hard age cap regardless of read state.
    #[serde(default = "RetentionConfig::default_purge_read_only")]
    pub purge_read_only: bool,
}

impl RetentionConfig {
    fn default_days() -> i64 {
        crate::settings::defaults::RETENTION_DAYS
    }
    fn default_purge_read_only() -> bool {
        crate::settings::defaults::RETENTION_PURGE_READ_ONLY
    }
}

impl Default for RetentionConfig {
    fn default() -> Self {
        RetentionConfig {
            days: Self::default_days(),
            purge_read_only: Self::default_purge_read_only(),
        }
    }
}

impl Config {
    /// Helper function to create a detailed error message showing all possible config file locations
    fn config_not_found_error(
        standard_path: &std::path::Path,
        local_path: &std::path::Path,
    ) -> String {
        let current_dir_path = std::env::current_dir()
            .unwrap_or_else(|_| std::path::PathBuf::from("current directory"));
        let current_dir = current_dir_path.to_string_lossy();

        format!(
            "Configuration file not found. Please create one at one of these locations:\n\
             \n\
             Standard OS configuration directory:\n\
             \x20• {}\n\
             \n\
             Local directory:\n\
             \x20• {}/config.toml\n\
             \x20• {} (when running from the project directory)\n\
             \n\
             You can also set the JWT secret via environment variable: FEED_JWT_SECRET",
            standard_path.to_string_lossy(),
            current_dir,
            local_path.to_string_lossy()
        )
    }

    pub fn from_file(path: &std::path::Path) -> Result<Self, Box<dyn std::error::Error>> {
        let contents = std::fs::read_to_string(path)?;
        let config: Config = toml::from_str(&contents)?;
        Ok(config)
    }

    /// Load configuration from OS-standard config directory or local config.toml.
    /// Environment variable FEED_JWT_SECRET can override the jwt_secret.
    pub fn load() -> Result<Self, Box<dyn std::error::Error>> {
        // First try the OS-standard config directory for the app named "feed"
        let mut config: Config;
        if let Some(proj_dirs) = ProjectDirs::from("eu.monniot", "", "feed") {
            let cfg = proj_dirs.config_dir().join("config.toml");
            if cfg.exists() {
                config = Config::from_file(&cfg)?;
            } else {
                // Fallback to local config.toml
                let local = std::path::Path::new("config.toml");
                if local.exists() {
                    config = Config::from_file(local)?;
                } else {
                    return Err(Self::config_not_found_error(&cfg, local).into());
                }
            }
        } else {
            // No ProjectDirs available; fallback to local
            let local = std::path::Path::new("config.toml");
            if local.exists() {
                config = Config::from_file(local)?;
            } else {
                // Show actual standard paths when ProjectDirs is not available
                let standard_config_path = if cfg!(target_os = "windows") {
                    "%APPDATA%\\feed\\config.toml"
                } else if cfg!(target_os = "macos") {
                    "$HOME/Library/Application Support/eu.monniot.feed/config.toml"
                } else {
                    "$HOME/.config/feed/config.toml"
                };
                return Err(Self::config_not_found_error(
                    std::path::Path::new(standard_config_path),
                    local,
                )
                .into());
            }
        }

        // Environment overrides for secrets (preferred to keep secrets out of config files)
        if let Ok(jwt_secret) = std::env::var("FEED_JWT_SECRET") {
            config.auth.jwt_secret = jwt_secret;
        }

        Ok(config)
    }

    /// Returns the database connection URL located in the OS-standard data
    /// directory for the `feed` application.
    pub fn database_url(&self) -> Result<String, Box<dyn std::error::Error>> {
        if let Some(url) = self.database.as_ref().and_then(|db| db.url.as_ref()) {
            return Ok(url.clone());
        } else if let Some(proj_dirs) = ProjectDirs::from("eu.monniot", "", "feed") {
            let data_dir = proj_dirs.data_dir();
            std::fs::create_dir_all(data_dir)?;
            let db_path = data_dir.join("feeds.db");
            let db_path_str = db_path
                .to_str()
                .ok_or("Failed to convert database path to string")?;
            return Ok(format!("sqlite://{}", db_path_str));
        }

        Err("Could not determine OS data dir".into())
    }
}

fn deser_from_str<'de, D, T>(d: D) -> std::result::Result<T, D::Error>
where
    D: serde::Deserializer<'de>,
    T: FromStr,
    <T as FromStr>::Err: std::fmt::Display,
{
    let s: String = serde::de::Deserialize::deserialize(d)?;
    let t = FromStr::from_str(&s)
        .map_err(|e: <T as FromStr>::Err| serde::de::Error::custom(format!("{e}")))?;

    Ok(t)
}
