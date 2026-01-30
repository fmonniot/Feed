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

impl Config {
    fn from_file(path: &std::path::Path) -> Result<Self, Box<dyn std::error::Error>> {
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
                    return Err("Configuration file not found in standard config directory or local 'config.toml'".into());
                }
            }
        } else {
            // No ProjectDirs available; fallback to local
            let local = std::path::Path::new("config.toml");
            if local.exists() {
                config = Config::from_file(local)?;
            } else {
                return Err("Configuration file not found in standard config directory or local 'config.toml'".into());
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
        if let Some(proj_dirs) = ProjectDirs::from("eu.monniot", "", "feed") {
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
    let s: &str = serde::de::Deserialize::deserialize(d)?;
    let t = FromStr::from_str(s)
        .map_err(|e: <T as FromStr>::Err| serde::de::Error::custom(format!("{e}")))?;

    Ok(t)
}
