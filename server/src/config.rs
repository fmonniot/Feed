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
                    &std::path::Path::new(standard_config_path),
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
        if let Some(url) = self.database.as_ref() .and_then(|db| db.url.as_ref()){
            return Ok(url.clone())
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
