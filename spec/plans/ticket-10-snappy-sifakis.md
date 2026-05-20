Date: 2026-05-19 20:07 -0700

# Ticket #10 — First-run DB bootstrap

## Context

`server/src/main.rs:71` has a TODO: the server doesn't handle first-run cleanly when no SQLite DB file exists. Two separate gaps exist:

1. **`create_if_missing` not set** — `Database::new()` calls `SqlitePoolOptions::connect(database_url)` with a plain URL string. SQLx's default for SQLite is `create_if_missing = false`, so connecting to a non-existent file will fail.
2. **Explicit `database.url` config doesn't create parent directories** — `Config::database_url()` calls `create_dir_all` for the OS-standard path case, but returns an explicit `database.url` as-is with no directory creation. If the configured path's parent doesn't exist, the connection fails.

Existing tests all use `NamedTempFile::new()` which creates the file before passing it to `Database::new()`, so neither gap is currently exercised.

## Implementation

### 1. `server/src/db.rs` — `Database::new()` (lines 111–119)

Replace the plain `connect(database_url)` call with:

- Parse the URL string to extract the filesystem path (strip `sqlite://` prefix; skip `:memory:` / empty)
- Check if the file already exists → `let is_new = !path.exists()` (before any mutations)
- `create_dir_all` on the parent directory
- Build `SqliteConnectOptions::from_str(database_url)?.create_if_missing(true)`
- Use `SqlitePoolOptions::connect_with(options)` instead of `connect(url)`
- After the pool is ready, `tracing::info!` a first-run log line if `is_new`

Add to the import at the top of `db.rs`:
```rust
use sqlx::sqlite::{SqliteConnectOptions, SqlitePool, SqlitePoolOptions};
use std::str::FromStr;
```

Path extraction helper (private, inline):
```rust
fn sqlite_file_path(url: &str) -> Option<std::path::PathBuf> {
    let path = url.strip_prefix("sqlite://")?;
    if path.is_empty() || path == ":memory:" { return None; }
    Some(std::path::PathBuf::from(path))
}
```

`io::Error` → `sqlx::Error` conversion: `sqlx::Error::Io(e)`.

### 2. `server/src/main.rs` — line 71

Remove the TODO comment. The fix now lives entirely in `Database::new()`.

### 3. `server/src/db_tests.rs` — add cold-start tests

Add inside the existing `mod db_tests` block, alongside the migration tests:

```rust
#[tokio::test]
async fn test_cold_start_creates_db_when_file_missing() {
    // Parent dir exists, file does not
    let tmpdir = tempfile::TempDir::new().unwrap();
    let db_path = tmpdir.path().join("feeds.db");
    assert!(!db_path.exists());
    let db_url = format!("sqlite://{}", db_path.display());
    let _db = crate::db::Database::new(&db_url).await.unwrap();
    assert!(db_path.exists());
}

#[tokio::test]
async fn test_cold_start_creates_missing_parent_dir() {
    // Neither the parent dir nor the file exist
    let tmpdir = tempfile::TempDir::new().unwrap();
    let db_path = tmpdir.path().join("subdir/feeds.db");
    assert!(!db_path.parent().unwrap().exists());
    let db_url = format!("sqlite://{}", db_path.display());
    let _db = crate::db::Database::new(&db_url).await.unwrap();
    assert!(db_path.exists());
}
```

These tests don't need `#[serial]` because they use independent temp directories.

## Files to modify

| File | Change |
|---|---|
| [server/src/db.rs](server/src/db.rs) | `Database::new()`: use `SqliteConnectOptions` + `create_if_missing(true)`, add parent-dir creation, add first-run log |
| [server/src/main.rs](server/src/main.rs) | Remove TODO comment at line 71 |
| [server/src/db_tests.rs](server/src/db_tests.rs) | Add two cold-start tests |

## Verification

```sh
cd server && cargo test
```

Expected: 99 passed (97 existing + 2 new cold-start tests); 6 ignored; 0 failed.

Existing-DB behaviour: all 97 existing tests still pass because `TestDatabase::new()` passes an already-created file path; `create_if_missing(true)` is a no-op when the file exists.
