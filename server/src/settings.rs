//! Typed settings accessor layer over the generic key/value `settings` table.
//!
//! The raw store ([`crate::db::Database::get_setting`] /
//! [`crate::db::Database::put_setting`]) deals in `String` values keyed by a
//! `&str`. This module sits on top of it and implements the **fallback chain**
//! every configurable knob obeys:
//!
//! ```text
//! persisted KV value  →  config-file value  →  built-in default
//! ```
//!
//! A fresh database with a populated `config.toml` therefore behaves exactly as
//! the config dictates; a database that has had a value persisted (e.g. via a
//! settings endpoint) overrides the config; and a database + config that say
//! nothing fall back to the built-in default constant in [`defaults`].
//!
//! Handlers and the scheduler should read typed values through [`Settings`]
//! rather than calling `get_setting` and parsing strings inline, so the fallback
//! chain and the wire encoding of each key live in exactly one place.

use crate::config::Config;
use crate::db::Database;

/// Built-in default values — the final tier of the fallback chain.
///
/// These are the single source of truth for defaults: the `[fetch]` /
/// `[retention]` config structs reference them via `serde(default = …)`, so an
/// absent config field and an absent config section resolve to the same value.
pub mod defaults {
    /// `[fetch].scheduler_tick_minutes` — how often the scheduler loop wakes.
    pub const SCHEDULER_TICK_MINUTES: i64 = 5;
    /// `[fetch].default_interval_minutes` — inherited by new feeds.
    pub const DEFAULT_FETCH_INTERVAL_MINUTES: i64 = 60;
    /// `[fetch].min_interval_minutes` — hard floor protecting upstreams.
    pub const MIN_FETCH_INTERVAL_MINUTES: i64 = 15;
    /// `[fetch].contact_url` — embedded in the outgoing User-Agent.
    pub const CONTACT_URL: &str = "https://github.com/fmonniot/Feed";
    /// `[fetch].respect_retry_after` — honor upstream `Retry-After` headers.
    pub const RESPECT_RETRY_AFTER: bool = true;
    /// `[retention].days` — how long articles are kept.
    pub const RETENTION_DAYS: i64 = 90;
    /// `[retention].purge_read_only` — only purge read articles when `true`.
    pub const RETENTION_PURGE_READ_ONLY: bool = true;
}

/// Persisted-KV key names. Centralised so handlers and the scheduler agree on
/// the exact strings written to the `settings` table.
pub mod keys {
    /// Default fetch interval (minutes) inherited by new feeds. Introduced by the
    /// fetch-cadence plan; consumed by `add_feed` once step 4 lands.
    #[allow(dead_code)] // consumed by step 4 (default-interval inheritance)
    pub const DEFAULT_FETCH_INTERVAL_MINUTES: &str = "default_fetch_interval_minutes";
    /// Retention window (days). The value `"forever"` means "never delete".
    pub const RETENTION_DAYS: &str = "retention_days";
    /// Whether the retention sweep only deletes read articles.
    pub const RETENTION_PURGE_READ_ONLY: &str = "retention_purge_read_only";
}

/// Retention window resolved through the fallback chain.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RetentionDays {
    /// Keep articles for this many days, then purge.
    Days(i64),
    /// Never purge (persisted as the literal string `"forever"`).
    Forever,
}

/// Typed accessor over the settings store.
///
/// Borrows a [`Database`] (the persisted tier) and a [`Config`] (the config-file
/// tier); the built-in defaults come from [`defaults`]. Cheap to construct — it
/// holds references only — so callers build one per request / per scheduler tick.
pub struct Settings<'a> {
    db: &'a Database,
    config: &'a Config,
}

impl<'a> Settings<'a> {
    /// Build an accessor over the given database and config.
    pub fn new(db: &'a Database, config: &'a Config) -> Self {
        Settings { db, config }
    }

    /// Default fetch interval (minutes) inherited by newly added feeds.
    ///
    /// Fallback chain: persisted `default_fetch_interval_minutes` →
    /// `[fetch].default_interval_minutes` → built-in default (60).
    ///
    /// A persisted value that fails to parse as an integer is ignored (treated as
    /// absent) so a garbled write can never poison the default; the config / built-in
    /// value is used instead.
    #[allow(dead_code)] // consumed by step 4 (default-interval inheritance in add_feed)
    pub async fn default_fetch_interval_minutes(&self) -> Result<i64, sqlx::Error> {
        if let Some(raw) = self.db.get_setting(keys::DEFAULT_FETCH_INTERVAL_MINUTES).await?
            && let Ok(parsed) = raw.parse::<i64>()
        {
            return Ok(parsed);
        }
        Ok(self.config.fetch.default_interval_minutes)
    }

    /// Retention window resolved through the fallback chain.
    ///
    /// Persisted `retention_days` (`"forever"` → [`RetentionDays::Forever`], an
    /// integer → [`RetentionDays::Days`]) → `[retention].days` → built-in default (90).
    /// An unparseable persisted value is ignored and the config / built-in value used.
    pub async fn retention_days(&self) -> Result<RetentionDays, sqlx::Error> {
        if let Some(raw) = self.db.get_setting(keys::RETENTION_DAYS).await? {
            if raw == "forever" {
                return Ok(RetentionDays::Forever);
            }
            if let Ok(parsed) = raw.parse::<i64>() {
                return Ok(RetentionDays::Days(parsed));
            }
        }
        Ok(RetentionDays::Days(self.config.retention.days))
    }

    /// Whether the retention sweep only deletes read articles.
    ///
    /// Persisted `retention_purge_read_only` (only the literal `"false"` opts out)
    /// → `[retention].purge_read_only` → built-in default (true). The safe value
    /// (preserve unread) wins for any absent/garbled persisted value.
    pub async fn retention_purge_read_only(&self) -> Result<bool, sqlx::Error> {
        if let Some(raw) = self.db.get_setting(keys::RETENTION_PURGE_READ_ONLY).await? {
            return Ok(crate::scheduler::resolve_purge_read_only(Some(&raw)));
        }
        Ok(self.config.retention.purge_read_only)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{
        AuthConfig, Config, FetchConfig, RetentionConfig, ServerConfig,
    };
    use crate::test_utils::TestDatabase;
    use argon2::password_hash::PasswordHashString;

    /// Build a Config whose `[fetch]`/`[retention]` sections can be customised.
    fn config_with(fetch: FetchConfig, retention: RetentionConfig) -> Config {
        Config {
            server: ServerConfig {
                host: "127.0.0.1".into(),
                port: 0,
            },
            auth: AuthConfig {
                username: "admin".into(),
                password_hash: PasswordHashString::new(
                    "$argon2id$v=19$m=65536,t=2,p=1$elZxeHB1VzhpcUliR3RkMA$pSockUc1J5m0mTLfKRb/mg",
                )
                .expect("valid hash"),
                jwt_secret: "test_jwt_secret_key_long_enough".into(),
            },
            database: None,
            web: None,
            fetch,
            retention,
        }
    }

    /// Tier 3 (built-in default): no persisted KV value and no overriding config
    /// → the typed accessor returns the built-in default constant.
    #[tokio::test]
    async fn fallback_to_builtin_default_when_kv_and_config_absent() {
        let test_db = TestDatabase::new().await.expect("db");
        // A wholly-default config == the `[fetch]`/`[retention]` sections being absent.
        let config = config_with(FetchConfig::default(), RetentionConfig::default());
        let settings = Settings::new(&test_db.db, &config);

        assert_eq!(
            settings.default_fetch_interval_minutes().await.expect("read"),
            defaults::DEFAULT_FETCH_INTERVAL_MINUTES
        );
        assert_eq!(
            settings.retention_days().await.expect("read"),
            RetentionDays::Days(defaults::RETENTION_DAYS)
        );
        assert_eq!(
            settings.retention_purge_read_only().await.expect("read"),
            defaults::RETENTION_PURGE_READ_ONLY
        );
    }

    /// Tier 2 (config file): no persisted KV value but a config value is present
    /// → the typed accessor returns the config value, not the built-in default.
    #[tokio::test]
    async fn fallback_to_config_when_kv_absent() {
        let test_db = TestDatabase::new().await.expect("db");
        let fetch = FetchConfig {
            default_interval_minutes: 120,
            ..FetchConfig::default()
        };
        let retention = RetentionConfig {
            days: 30,
            purge_read_only: false,
        };
        let config = config_with(fetch, retention);
        let settings = Settings::new(&test_db.db, &config);

        assert_eq!(
            settings.default_fetch_interval_minutes().await.expect("read"),
            120
        );
        assert_eq!(
            settings.retention_days().await.expect("read"),
            RetentionDays::Days(30)
        );
        assert!(
            !settings.retention_purge_read_only().await.expect("read"),
            "config purge_read_only=false should be honored when KV is absent"
        );
    }

    /// Tier 1 (persisted KV): a persisted value beats both the config value and the
    /// built-in default for every typed key.
    #[tokio::test]
    async fn persisted_kv_value_wins_over_config_and_default() {
        let test_db = TestDatabase::new().await.expect("db");
        // Config says 120 / 30 days / false — the KV writes below must all win.
        let fetch = FetchConfig {
            default_interval_minutes: 120,
            ..FetchConfig::default()
        };
        let retention = RetentionConfig {
            days: 30,
            purge_read_only: false,
        };
        let config = config_with(fetch, retention);

        test_db
            .db
            .put_setting(keys::DEFAULT_FETCH_INTERVAL_MINUTES, "45")
            .await
            .expect("put");
        test_db
            .db
            .put_setting(keys::RETENTION_DAYS, "7")
            .await
            .expect("put");
        test_db
            .db
            .put_setting(keys::RETENTION_PURGE_READ_ONLY, "true")
            .await
            .expect("put");

        let settings = Settings::new(&test_db.db, &config);
        assert_eq!(
            settings.default_fetch_interval_minutes().await.expect("read"),
            45
        );
        assert_eq!(
            settings.retention_days().await.expect("read"),
            RetentionDays::Days(7)
        );
        assert!(
            settings.retention_purge_read_only().await.expect("read"),
            "persisted true should override config false"
        );
    }

    /// The persisted `"forever"` sentinel resolves to [`RetentionDays::Forever`],
    /// overriding any finite config value.
    #[tokio::test]
    async fn persisted_forever_resolves_to_forever() {
        let test_db = TestDatabase::new().await.expect("db");
        let retention = RetentionConfig {
            days: 30,
            ..RetentionConfig::default()
        };
        let config = config_with(FetchConfig::default(), retention);
        test_db
            .db
            .put_setting(keys::RETENTION_DAYS, "forever")
            .await
            .expect("put");

        let settings = Settings::new(&test_db.db, &config);
        assert_eq!(
            settings.retention_days().await.expect("read"),
            RetentionDays::Forever
        );
    }

    /// A garbled persisted integer is ignored (treated as absent) so it cannot
    /// poison the resolved value — the config tier is used instead.
    #[tokio::test]
    async fn garbled_persisted_value_falls_through_to_config() {
        let test_db = TestDatabase::new().await.expect("db");
        let fetch = FetchConfig {
            default_interval_minutes: 90,
            ..FetchConfig::default()
        };
        let config = config_with(fetch, RetentionConfig::default());
        test_db
            .db
            .put_setting(keys::DEFAULT_FETCH_INTERVAL_MINUTES, "not-a-number")
            .await
            .expect("put");

        let settings = Settings::new(&test_db.db, &config);
        assert_eq!(
            settings.default_fetch_interval_minutes().await.expect("read"),
            90
        );
    }
}
