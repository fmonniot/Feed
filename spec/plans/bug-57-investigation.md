# BUG-57 investigation — read articles resurrected as unread

**Date:** 2026-07-11 06:49 PDT (updated with production results; original analysis 2026-07-11 00:20 PDT)

## Root cause (CONFIRMED — DB-level repro test + production data)

The nightly retention sweep and the feed-fetch pipeline interact to resurrect
previously-read articles as brand-new unread articles:

1. **3 AM daily sweep** (`server/src/scheduler.rs:437`) calls
   `delete_old_articles(retention_days=90, purge_read_only=true)`
   (`server/src/db.rs:1646`), which `DELETE`s every **read** article older
   than 90 days (`COALESCE(published, fetched_at) < cutoff AND is_read = 1`).
2. **Next fetch tick** re-parses each feed's XML. For any feed whose XML still
   lists entries older than 90 days (archive-heavy feeds, full-history Atom
   feeds), the fetch pipeline (`server/src/fetcher.rs:469`) calls
   `add_article` (`server/src/db.rs:1515`) with the same `(feed_id, guid)`.
3. `add_article` uses `INSERT OR IGNORE`, relying on the
   `UNIQUE(feed_id, guid)` constraint to skip known articles. But the row was
   just deleted — nothing remembers the guid. The `deleted_articles` tombstone
   table (migration v20, `server/src/db.rs:931`) stores only the numeric `id`
   for client delta-sync, and `add_article` never consults it.
4. The article is re-inserted with `is_read = 0` (column default), gets a new
   `id` and a fresh `seq`, so delta-sync pushes it to all clients as a new
   unread article. The cycle repeats every night for every read article that
   is both >90 days old and still present in its feed's XML.

Reproduction test: `test_bug57_retention_purge_resurrects_read_article_as_unread`
in `server/src/db_tests.rs` (passes against current main — i.e. exhibits the bug).

Why "up to 2000": the first sweep after a long uptime purges the whole >90-day
read backlog at once; clients then receive the resurrected articles via
`GET /v1/sync`, which pages at a 2000-article clamp.

## Production confirmation results (2026-07-11, `/var/lib/feed/data/feeds.db`)

The diagnostic script below was run against the production database:

- **Query A (resurrection signature): confirmed.** 20+ feeds carry unread
  articles published >90 days ago but fetched within the last week. Top
  offenders: `feeds.feedburner.com/weborama` (397),
  `mangahere.co/rss/relife.xml` (244),
  `AndroidDevelopersBackstage` (223), and a long tail of mangafox.me/fanfox.net
  feeds (~40–125 each) — exactly the archive-heavy feed profile the mechanism
  predicts (manga RSS feeds list the full chapter history).
- **Query B (tombstone volume): 10,330 rows** in `deleted_articles` — the
  sweep has been deleting at scale, consistent with nightly purges feeding the
  resurrection cycle.
- **Query C (unstable guids): effectively ruled out.** Only 2 links in the
  whole DB have 2 guids each (jmoiron.net, dancarlin.com) — noise, not a
  systemic cause. The feed-rs synthesized-guid suspect is NOT the driver of
  this bug; no separate fix needed for BUG-57.

Conclusion: the retention-purge → refetch-reinsert cycle is the root cause.

## Secondary suspect (ruled out by query C above)

For feeds without native entry ids, feed-rs 2.4 synthesizes the guid as
`siphash(first-link + title)` (`feed-rs/src/parser/mod.rs::generate_id`). If a
publisher edits a title (or the entry has neither link nor title → random
UUID), the guid changes and the entry re-inserts as unread. Signature:
duplicate `(feed_id, link)` rows with different guids. Query C found only 2
such links in production — not a systemic cause; no action needed for BUG-57.

## Production confirmation (run against the live sqlite DB)

```sh
sqlite3 /path/to/prod.db <<'SQL'
-- A. Resurrection signature: unread articles published >90d ago but fetched recently.
SELECT f.url, COUNT(*) AS resurrected
FROM articles a JOIN feeds f ON f.id = a.feed_id
WHERE a.is_read = 0
  AND a.published IS NOT NULL
  AND a.published < strftime('%s','now') - 90*24*3600
  AND a.fetched_at > strftime('%s','now') - 7*24*3600
GROUP BY f.url ORDER BY resurrected DESC LIMIT 20;

-- B. Tombstone volume: how many deletes the sweep has issued (id-only tombstones).
SELECT COUNT(*) FROM deleted_articles;

-- C. Unstable-guid signature: same link, multiple guids within one feed.
SELECT feed_id, link, COUNT(DISTINCT guid) AS n
FROM articles WHERE link IS NOT NULL
GROUP BY feed_id, link HAVING n > 1
ORDER BY n DESC LIMIT 20;
SQL
```

Also check server logs around 03:00 for `Deleted N articles older than 90 days`
followed by fetch-tick inserts of comparable size.

## Fix directions (not yet implemented)

- **Guid-aware retention:** don't purge articles whose guid is still present in
  the feed's latest fetch, OR keep a `(feed_id, guid)` purge ledger and have
  `add_article` skip guids that were retention-purged (needs its own aging
  policy so the ledger doesn't grow forever).
- **Cheapest correct option:** instead of `DELETE`, keep the row but drop the
  heavy columns (`content = NULL`) for retention-aged read articles; the
  `UNIQUE(feed_id, guid)` guard then keeps working naturally. Requires
  filtering these "compacted" rows out of client-facing queries or serving
  them without content.
- Whatever the fix, `test_bug57_retention_purge_resurrects_read_article_as_unread`
  should be inverted (assert the article does NOT come back unread) and kept
  as the regression test.
