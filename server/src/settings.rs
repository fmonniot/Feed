pub mod keys {
    pub const RETENTION_DAYS: &str = "retention_days";
    pub const RETENTION_PURGE_READ_ONLY: &str = "retention_purge_read_only";
}

pub mod defaults {
    pub const RETENTION_DAYS: i64 = 90;
}
