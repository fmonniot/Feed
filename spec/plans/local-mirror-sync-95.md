# Local-mirror article sync — design proposal (#95)

**Date:** 2026-06-25 05:49 PDT · **Updated:** 2026-06-25 07:01 PDT

**Status:** Design locked — ready to implement. A **change-log / sequence-based sync** is the chosen approach (see §0 for how we got here). No implementation in this session. The one remaining in-scope deferral is the web storage backend (§6.B), decided once the protocol is in build; all other decisions are settled in the body.

**Predecessor:** the page-follow bug fix has **landed** — both repos call `FeedApi.getAllArticles` / `getAllFeedArticles`. This proposal replaces "page-follow the whole list on every refresh" with a true incremental local mirror.

---

## 0. How we chose B (one-paragraph history)

We compared **A — `id`-delta + periodic full `(id,is_read)` reconcile** against **B — a sequence/change-log delta**. A's premise was "the reconcile sweep is free at small scale." The deployed instance has **~20,000 live articles and grows structurally** (retention defaults to `purge_read_only = true`, [db.rs:1525](../../server/src/db.rs#L1525), so unread articles are never purged and accumulate indefinitely). At that size A means shipping ~200 KB-and-growing per reconcile, and incremental deletions fundamentally require recording *what* was deleted anyway (a tombstone) — so A buys complexity without escaping B's core mechanism. **B ships bytes proportional to what changed, flat regardless of corpus size.** Decision: B.

---

## 1. Server facts this design is built on

1. **Articles are immutable after insert.** `INSERT OR IGNORE INTO articles` ([db.rs:1403](../../server/src/db.rs#L1403)); a re-fetched feed item with an existing `(feed_id, guid)` is dropped, never updated. **Consequence: the only mutations to an article row are `is_read` (user) and `link_status`/`link_checked_at` (server-side HEAD probes). No content-update case exists.**
2. **`articles.id` is a rowid that orders inserts but is not stable over time.** It's `INTEGER PRIMARY KEY` *without* `AUTOINCREMENT` ([db.rs:407](../../server/src/db.rs#L407)), so (a) it only orders inserts — it can't express updates or deletes, which is why this design introduces a separate `seq`; and (b) SQLite **reuses** the largest rowid after the max-id row is deleted, so an `id` is not a permanently-unique key. Both properties shape the schema below: `seq` is the sync axis, and tombstones key on `seq` (never reused), not on `id` (§3.1).
3. **Today's `since` filters `published >= s`** ([db.rs:1447](../../server/src/db.rs#L1447)) — unusable as a sync cursor (`published` is nullable, backdate-able, non-monotonic). We replace it with a `seq` cursor.
4. **Two deletion drivers:** retention purge (scheduled, age-based, read-only by default — [db.rs:1519](../../server/src/db.rs#L1519), [scheduler.rs:425](../../server/src/scheduler.rs#L425)) and feed-delete **cascade** (`ON DELETE CASCADE`, [db.rs:414](../../server/src/db.rs#L414)). The cascade deletes article rows at the **DB level**, bypassing application code — so tombstones must be captured by a **trigger**, not by app code in `delete_feed`.
   - *Could we change this (drop the cascade, write tombstones in app code instead)? We could, but it's the wrong trade. Retention's purge is also a bulk `DELETE FROM articles WHERE …` ([db.rs:1526](../../server/src/db.rs#L1526)), not row-by-row app logic, so app-level tombstoning would have to be duplicated there too — and any future delete path would silently skip tombstones. A single `AFTER DELETE` trigger covers **every** delete path uniformly and cannot be forgotten. Keep the cascade; let the trigger own tombstones.*
5. **Triggers are the right mechanism, and two trigger families share the `articles` UPDATE event.** FTS triggers already mirror `articles` AFTER INSERT/UPDATE/DELETE into `articles_fts` ([db.rs:590-614](../../server/src/db.rs#L590)), so the seq machinery follows an established precedent. Two facts govern how triggers interact here:
   - **`recursive_triggers` is OFF** (only `PRAGMA foreign_keys = ON` is set, [db.rs:320](../../server/src/db.rs#L320)), so a trigger that writes back to `articles` does **not** re-fire *itself*. This is what lets the seq triggers stamp `seq` onto the row they fired for.
   - **A trigger does still fire *other* triggers on the same event.** The seq triggers' write-back (`UPDATE articles SET seq = …`) is an `articles` UPDATE, so any *other* `AFTER UPDATE` trigger fires on it. Today's FTS update trigger is unscoped (`AFTER UPDATE ON articles`, [db.rs:612](../../server/src/db.rs#L612)), so left as-is it would re-index full-text content on every `seq` write and every `is_read` toggle. The design therefore scopes **both** trigger families by column (§3.2): the FTS trigger to `title`/`content` (the only inputs it depends on), the seq UPDATE trigger to `is_read`. Each fires only for the updates it cares about, and neither fires for the other's write-back. Because articles are immutable (§1.1), the scoped FTS trigger effectively never fires after insert — which is correct, and also removes a pre-existing redundant reindex on every read-state toggle.
6. **Scale:** ~20k live articles, growing. Tombstone rows are tiny (≈24 bytes); even 10k deletions/year ≈ 240 KB/year.

---

## 2. The three sub-problems, and how the design unifies them

- **P1 — new articles:** rows the client hasn't seen.
- **P2 — changed read-state:** you run **both** Android and Web; a mark-as-read on one must converge on the other. (Implied by "badge == list for every tab" on a two-client setup; the ticket doesn't name it.)
- **P3 — deletions:** retention + feed-cascade.

The design handles all three as one ordered stream keyed by a monotonic `seq`: inserts and `is_read` changes update `articles.seq`; deletions write a tombstone carrying a `seq`. A single endpoint returns everything with `seq > cursor`.

---

## 3. Server design

### 3.1 Schema additions (one new `if version < 20` block in the inline migration chain)

This adds a single block to the `if version < N { … }` chain in `Database::new` ([db.rs](../../server/src/db.rs); the chain currently reaches `version < 19`). Everything below — `sync_counter`, the `seq` column, `deleted_articles`, the backfill, and the trigger (re)creations of §3.2 — lives under that one version guard.

```sql
-- monotonic source shared by article changes AND tombstones.
-- The CHECK(id = 0) PK pins this to a single row: a stray second row
-- can't silently desync the one piece of global mutable state.
CREATE TABLE sync_counter (
    id    INTEGER PRIMARY KEY CHECK (id = 0),
    value INTEGER NOT NULL
);
INSERT INTO sync_counter (id, value) VALUES (0, 0);

ALTER TABLE articles ADD COLUMN seq INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_articles_seq ON articles(seq);

CREATE TABLE deleted_articles (
    seq  INTEGER PRIMARY KEY,   -- the seq of the deletion; unique across both tables (§3.3)
    id   INTEGER NOT NULL       -- the deleted article's id (plain column, may repeat)
);
CREATE INDEX idx_deleted_articles_id ON deleted_articles(id);
```

The tombstone keys on `seq`, not on `id`, because `id` is reusable (§1.2): a feed-cascade or retention purge can delete the highest-id article, after which the next insert takes the same rowid. With `id` as the PK, the sequence *delete max-id article → tombstone(id=N) → insert reuses id=N → delete again* would hit a PRIMARY KEY conflict on the second tombstone and fail the delete (and the cascade/purge it's part of). `seq` comes from the monotonic counter and is never reused, so it's a stable PK; `id` is a plain column the client passes to `deleteByIds`. The index on `id` isn't used by the sync query (which reads by `seq`) — it's there only in case a future path needs to look up tombstones by article id, and can be dropped if it never does (E13).

**Backfill at migration (step order matters):**
1. `CREATE TABLE sync_counter`, `INSERT … VALUES (0)`.
2. `ALTER TABLE articles ADD COLUMN seq INTEGER NOT NULL DEFAULT 0`.
3. Backfill `seq` directly from `id`:
   ```sql
   UPDATE articles SET seq = id;
   UPDATE sync_counter SET value = (SELECT COALESCE(MAX(id), 0) FROM articles);
   ```
   `seq = id` reuses the existing rowid as the initial total order. It's O(n) (one pass, no self-join), unique (ids are unique at a point in time), and monotonic with insert order — which is all the protocol needs; `seq` is never required to be dense, only unique and increasing. A *ranking* backfill (`seq = (SELECT COUNT(*) FROM articles a2 WHERE a2.id <= articles.id)`) would produce the same order but is O(n²) — ~400M comparisons over 20k rows — for no benefit. Setting the counter to `MAX(id)` guarantees every post-migration `seq` exceeds every backfilled one.
4. `CREATE TABLE deleted_articles` + indexes.
5. **Create the seq triggers last.**

Creating triggers after the backfill keeps the bulk backfill `UPDATE` from interacting with them at all. (Even if reordered, the `au` seq trigger is `UPDATE OF is_read` so a `seq`-only backfill wouldn't fire it — but ordering it last makes the migration obviously inert and matches the FTS-trigger placement precedent.) `id` order is a fine initial total order; `published` is not monotonic, but for backfill any stable order works since a fresh client takes the whole set anyway.

### 3.2 Triggers (mirror the FTS-trigger pattern)

A tiny helper expression `(SELECT value FROM sync_counter)` reads the counter; each trigger bumps then stamps.

```sql
-- new article: stamp seq
CREATE TRIGGER articles_seq_ai AFTER INSERT ON articles BEGIN
    UPDATE sync_counter SET value = value + 1;
    UPDATE articles SET seq = (SELECT value FROM sync_counter) WHERE id = NEW.id;
END;

-- read-state (and other user-visible columns) change: re-stamp seq.
-- UPDATE OF deliberately EXCLUDES link_status / link_checked_at (see §5, link_status decision).
CREATE TRIGGER articles_seq_au AFTER UPDATE OF is_read ON articles BEGIN
    UPDATE sync_counter SET value = value + 1;
    UPDATE articles SET seq = (SELECT value FROM sync_counter) WHERE id = NEW.id;
END;

-- deletion (explicit retention DELETE *and* feed cascade): write a tombstone
CREATE TRIGGER articles_seq_ad AFTER DELETE ON articles BEGIN
    UPDATE sync_counter SET value = value + 1;
    INSERT INTO deleted_articles (seq, id)
      VALUES ((SELECT value FROM sync_counter), OLD.id);
END;
```

Why triggers and not app code: the **cascade delete bypasses app code** (§1.4), so the tombstone *must* be a trigger; keeping insert/update seq as triggers too makes the mechanism uniform and matches the existing FTS precedent. Each seq trigger is scoped to the column it reacts to (`articles_seq_au` is `UPDATE OF is_read`), so it never fires for the FTS trigger's text writes or for its own `seq` write-back; `recursive_triggers` being OFF (§1.5) guarantees the write-back doesn't re-fire the seq trigger itself.

The same column-scoping has to be applied to the existing FTS update trigger, which is currently unscoped (`AFTER UPDATE ON articles`) and would otherwise re-index full-text content on every `seq` write-back and every `is_read` toggle (§1.5). Re-create it scoped to the only columns it reads:

```sql
DROP TRIGGER articles_au;
CREATE TRIGGER articles_au AFTER UPDATE OF title, content ON articles BEGIN
    INSERT INTO articles_fts(articles_fts, rowid, title, content)
      VALUES ('delete', OLD.id, OLD.title, OLD.content);
    INSERT INTO articles_fts(rowid, title, content)
      VALUES (NEW.id, NEW.title, NEW.content);
END;
```

Since `title`/`content` never change after insert (§1.1), this trigger effectively never fires post-insert — correct, since FTS only needs reindexing when text changes, and a clean improvement over today's trigger which reindexes on every read-state toggle (E14). This `DROP`/recreate lives in the same `if version < 20` block as the seq schema (§3.1).

**Insert-path write amplification.** `articles_seq_ai` turns every insert into an insert *plus* a counter bump *plus* a write-back `UPDATE` on the just-inserted row (which also re-touches `idx_articles_seq`). Feed fetches insert in bulk, so this roughly doubles writes on the hottest path. It's almost certainly fine — the rows are already in the page cache from the insert, and SQLite batches within the statement's transaction — but the design assumes rather than measures it. Validate with a bulk feed-fetch insert benchmark (T13), measured on CI per the project's "measure perf on CI, not local" rule, before treating the cost as settled.

> **Alternative considered & rejected — an append-only `change_log` with AUTOINCREMENT.** A single append-only log (one row per insert/update/delete, AUTOINCREMENT = free seq, pure triggers, no counter table) is elegant and also cascade-safe. Rejected because the workload is **read-heavy**: every is_read toggle appends a log row, so the log grows without bound between compactions and the sync query must dedupe to the latest op per article. **The seq-on-row + tombstones approach above is self-compacting** — one row per live article, updated in place — so steady-state storage is bounded by the corpus, and the only ever-growing table is the tiny tombstone list (§3.4). We accept a one-row counter table as the price.

### 3.3 The sync endpoint

`GET /v1/sync?since=<seq>&limit=<n>` (auth as today). `since` defaults to `0`. `limit` is **optional with a server default of 500 and a hard ceiling of 2000** — a request omitting it gets 500; a request above the ceiling is clamped (not rejected), so a fresh client backfilling 20k rows pages predictably and a buggy/hostile `limit=1000000` can't force a 20k-row response. Response:

```json
{
  "articles":    [ /* full Article rows with seq > since, ascending by seq */ ],
  "deleted_ids": [ /* ids from deleted_articles with seq > since */ ],
  "cursor":      <seq>,        // advance the client cursor to this
  "has_more":    <bool>        // true ⇒ call again with since = cursor
}
```

When the cursor is unrecoverable (`since > sync_counter.value`, §3.4) the endpoint returns a distinct single-field variant instead of the delta body, and the client clears its store and re-backfills from `since = 0`:

```json
{ "full_resync": true }
```

A normal delta response never carries `full_resync`; the client treats its presence as the signal regardless of the other fields, so the two shapes are unambiguous.

**Single seq axis.** `articles.seq` and `deleted_articles.seq` draw from the **same** counter, so each seq value is used exactly once across both tables. The change stream is the union of the two, sorted by seq.

**Pagination rule (contiguous, no split):** let the candidate seqs be every seq `> since` across both tables.
- If at most `limit` exist → return them all; `cursor = sync_counter.value`; `has_more = false`.
- Else → let `cursor` = the `limit`-th smallest such seq; return every change with `since < seq <= cursor`; `has_more = true`.

This guarantees the cursor never advances past a seq that wasn't fully delivered, even though the two streams are interleaved.

The `cursor` (the `limit`-th smallest seq across the union) is one query over both seq columns:

```sql
-- cursor for a paged response: the limit-th smallest seq > :since across both streams.
-- If this returns no row, there are <= :limit candidates -> cursor = sync_counter.value, has_more = false.
SELECT seq FROM (
    SELECT seq FROM articles         WHERE seq > :since
    UNION ALL
    SELECT seq FROM deleted_articles WHERE seq > :since
) ORDER BY seq LIMIT 1 OFFSET (:limit - 1);
```

The article and tombstone payloads are then two straightforward range reads bounded by that cursor (`seq > :since AND seq <= :cursor`, each `ORDER BY seq`), both served by `idx_articles_seq` / the `deleted_articles` PK. Keying the cutoff on a concrete seq value (not on `OFFSET :limit` over the merged stream per payload) is what keeps the two range reads consistent with each other.

**Backfill is just `since = 0`.** A fresh client calls with `since = 0` and pages until `has_more = false`. When `since = 0` the server **omits tombstones** (a client with no local data has nothing to delete) — a small optimization that also keeps the first-run payload to live rows only. After draining, the client holds the full mirror and a live cursor; every later sync is a true delta. **One code path for backfill and steady-state.**

**Concurrency note.** The server runs a `SqlitePoolOptions::new().max_connections(5)` pool ([db.rs:307](../../server/src/db.rs#L307)). SQLite serializes writers (one write lock), so the counter bump + seq stamp stay atomic against concurrent writes regardless of the pool — E2 holds as stated. What the pool *does* change is read/write contention: `/v1/sync` is a read-heavy query that can now run on one connection while a feed-fetch insert holds the write lock on another. There is no `journal_mode = WAL` in the current setup ([db.rs:320](../../server/src/db.rs#L320) sets only `foreign_keys`), so under the rollback journal a concurrent reader can see `SQLITE_BUSY`. This is a pre-existing property, but the sync endpoint makes it easier to hit; enabling WAL (readers don't block on the writer) and a `busy_timeout` is cheap hardening worth doing alongside this work.

### 3.4 Tombstone lifecycle

**Keep tombstones indefinitely** at launch (they're ~24 bytes; see §1.6). This deliberately **eliminates the staleness handshake** that a GC'd tombstone table would force: with all tombstones retained, *any* client cursor ≥ 0 always sees every deletion relevant to it. The only defensive case left is a **malformed/impossible cursor** (`since > sync_counter.value`, e.g. a restored-from-backup server) → respond with a `{ "full_resync": true }` body flag; the client clears its store and re-backfills from `since = 0`.

Pruning is **deferred, not free forever** — the tombstone table is the one append-only structure that grows without bound. A lazy GC (prune tombstones older than the longest plausible client-offline window, reintroducing `full_resync` for cursors older than the prune horizon) is tracked as a follow-up ticket in §6. We start without it.

### 3.5 API surface changes

A nice consequence of moving to a single sync stream + local badge: several endpoints lose their only consumer. **In this repo the clients are the only API consumers**, so these are safe to delete (grep-verify each during implementation).

**Added**
- `GET /v1/sync` — the only article-fetch path clients use after this change.

**Removed** (no remaining consumer)
- `GET /v1/feeds/{id}/articles` (`get_feed_articles_handler`, [main.rs:115](../../server/src/main.rs#L115)) — PR 72; feed selection is now a local filter (§4.5).
- `GET /v1/articles` (`get_articles_handler`, [main.rs:136](../../server/src/main.rs#L136)) — the global list backing the retired page-follow `getAllArticles`; superseded by `/v1/sync` (backfill is `since=0`).
- `GET /v1/articles/unread-count` (`get_unread_count_handler`, [main.rs:140](../../server/src/main.rs#L140)) — the badge is computed locally from the mirror (§4.4). **Note:** the underlying DB method `get_total_unread_count` stays — the stats handler still uses it ([handlers.rs:1467](../../server/src/api/handlers.rs#L1467)); only the route is removed.
- The `unread_count` field on the `/v1/feeds` response becomes unused by clients; drop it (or leave it dormant — removing is cleaner since the client is the only reader).

**Kept**
- `GET /v1/articles/search` — full-text search (#5), orthogonal to sync.
- The read-status **write** path (`POST /v1/articles/read`, `/read-all`, single-article read `PUT`) — this is what mutates `is_read` and triggers the server-side `seq` bump. Unchanged.
- `GET /v1/feeds` — still the wholesale source for feed metadata (titles, paused, etc.).

---

## 4. Client design (shared + both platforms)

### 4.0 Push the logic into `shared/`

Yes — this rework is the right moment to collapse the two duplicated `FeedRepository` implementations ([app](../../app/src/main/java/eu/monniot/feed/FeedRepository.kt), [web](../../web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt)) into one. Today each re-implements refresh/merge/map logic. Under the mirror model, everything except the two genuinely platform-specific backends is platform-independent and moves to `commonMain`:

- **`commonMain` owns:** the `SyncEngine` (the §4.1 loop + pagination follow + cursor advance + `full_resync` handling), the `Article → ArticleItem` mapping, the local badge count, feed-filtering, and the `FeedRepository` surface the `FeedViewModel` already consumes.
- **Each platform provides only two things, behind `commonMain` interfaces:**
  1. the **HTTP client** — already shared (`FeedApi` over Ktor);
  2. a persistent **`ArticleStore`** — the sole new platform-specific piece:

```kotlin
// commonMain
interface ArticleStore {
    suspend fun upsert(articles: List<Article>)      // REPLACE by id
    suspend fun deleteByIds(ids: List<Int>)

    // Read side is windowed and aggregate — never "load every row". See note below.
    fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>>
    fun observeUnreadCount(filter: ArticleFilter): Flow<Int>   // SQL COUNT, rows never materialized

    suspend fun cursor(): Long                        // 0 for a fresh install
    suspend fun setCursor(seq: Long)
    suspend fun clear()                               // for full_resync
}
```

`ArticleFilter` is the local selection (all / unread-only / a `feed_id`); ordering is fixed at `published DESC` with `seq DESC` as the tie-break so a nullable, non-monotonic `published` (§1.3) still yields a single deterministic order across both clients (E10).

**The read side is deliberately windowed, not whole-corpus.** The entire premise of approach B is a corpus that is "~20k live and growing structurally" (§0) — so the store contract must never hand the UI the full list. A naïve `observeArticles(): Flow<List<Article>>` would re-materialize 20k+ rows on every change, and ~40 times over during a paged backfill as the list grows. Instead:
- the list is a **windowed/paged observation** (`observePage`, backed by Android `Paging 3`/`PagingSource` and a web range query), so the UI holds one screenful, not the corpus;
- the badge is `observeUnreadCount`, a `SELECT COUNT(*) WHERE !is_read [AND feed_id = ?]` that **never loads rows** — the badge stays O(1)-ish even as the mirror grows.

This keeps §4.4 ("badge == list by construction") true without ever pulling the whole mirror into memory: badge and list read the same rows through the same filter, but each reads only what it needs.

Android implements `ArticleStore` with Room (Room exposes both `PagingSource` and `Flow<Int>` counts natively); web with its chosen backend (§6), which must satisfy the same windowed-read + count contract. The `SyncEngine`, badge, filtering, and mapping are written **once** and tested **once** in `shared/commonTest` — `app/` and `web/` shrink to a thin DB adapter each. This matches the module-ownership table in CLAUDE.md (shared owns `FeedRepository`/`FeedViewModel`; platforms own the DB impl).

### 4.1 Sync loop & apply order

```
client.sync():
  do:
    r = GET /v1/sync?since=cursor&limit=N
    store.upsertArticles(r.articles)     # P1 inserts + P2 read-state (REPLACE by id)
    store.deleteByIds(r.deleted_ids)     # P3
    cursor = r.cursor
    persistCursor(cursor)                # survive process death
  while r.has_more
```

Apply order within a page is upsert-then-delete; since seq is unique across both streams and the page is contiguous, the net effect is order-independent (an id can't be both upserted and tombstoned within one page). Upsert is `REPLACE`-by-id — content is immutable (§1.1), so in practice it only ever changes `is_read`.

**When sync runs (decided):** no new timer. `SyncEngine.sync()` is invoked by the client's **existing scheduled-fetch mechanism** plus manual pull-to-refresh — the same triggers that drive refresh today. The server already schedules upstream feed fetches independently; the client just pulls the resulting deltas on its existing cadence.

### 4.2 Cursor persistence
- **Android:** a one-row settings table in Room (or a `meta` table alongside `rss_items`).
- **Web:** a `meta` record in the persistent store (backend TBD — see §6).
Cold start resumes from the stored cursor; a brand-new install starts at `0`.

### 4.3 Read-state direction (LWW, single-user)
Read-state writes stay immediate `PUT`s (as today) which bump the server's seq; the next pull echoes the client's own change back (a harmless idempotent re-apply). Cross-device convergence falls out for free. **Known limitation:** a read-state change made while offline and not yet `PUT` could be clobbered by a pull that returns the older server state. The current app already `PUT`s synchronously, so this only bites a future offline-mutation-queue — tracked as a follow-up ticket in §6, not solved here.

### 4.4 Badge becomes local
With a full local mirror, the unread badge is computed **from the store** (`count where !isRead`), not from the server's `unread_count`. This is what makes `badge == list` true **by construction** for the Unread tab, All tab, and per-feed view — they read the same local rows. The now-orphaned server count endpoint is dropped — see §3.5.

### 4.5 Feed-selection becomes a local filter
No per-feed network fetch. `refreshForFeed` / `getFeedArticles` and the `/v1/feeds/{id}/articles` client path (PR 72) are **removed**; selection filters the local store by `feed_id`. Feed *metadata* (titles, paused, etc.) is small and still fetched wholesale via `GET /v1/feeds`. The removable server endpoints are catalogued in §3.5.

---

## 5. Edge-case catalogue & decisions

| # | Edge case | Resolution |
|---|---|---|
| E1 | **Feed-delete cascade** deletes many article rows at the DB level, bypassing app code | Tombstones are written by the `AFTER DELETE` **trigger**, which fires per cascaded row. ✅ covered by design. |
| E2 | **`seq` uniqueness / monotonicity under concurrent writes** | SQLite serializes writers; the counter bump + stamp happen in the writing statement's transaction. One seq per row-op. |
| E3 | **Trigger recursion** (au trigger updates `articles`, re-firing au) | `recursive_triggers` is OFF by default (§1.5). Add a test asserting it stays off. |
| E4 | **`link_status` churn** — server HEAD-probe jobs (#64) update many rows | The `au` trigger is `AFTER UPDATE OF is_read` — it **excludes** `link_status`/`link_checked_at`, so probes generate **no** sync traffic. **Decided: exclude.** link_status is eventually-stale on clients — delivered on backfill and whenever `is_read` changes, otherwise not pushed; the "broken link" indicator (#94 / BUG-32 area) is advisory, so staleness is acceptable. A future middle ground (restamp only when `link_status` crosses the dead-link boundary, not on every re-probe) can revisit if stale indicators prove annoying. Rejected alternative: adding those columns to `UPDATE OF`, which makes probe sweeps dominate sync bandwidth at 20k rows. |
| E5 | **Backfill payload** — `since=0` over 20k rows in one response is huge | `/v1/sync` paginates; backfill is just paging from `since=0` until `has_more=false`. |
| E6 | **Interleaved two-stream pagination splitting a seq** | The contiguous pagination rule in §3.3 advances `cursor` only to a fully-delivered seq. |
| E7 | **Article inserted then deleted between two of a client's pages** | Client may see it upserted in one page and tombstoned in a later page → net deleted. Idempotent. |
| E8 | **Restored-from-backup server** makes a client cursor `> counter` | `full_resync` signal → client clears + re-backfills (§3.4). |
| E9 | **Migration of the existing 20k rows** | Backfill `seq` in `id` order, set counter to max (§3.1). Existing *clients* re-backfill via `since=0`; per-client migration is out of scope (ticket). |
| E10 | **Display ordering** | `seq` is only the sync cursor; the UI orders by `published DESC, seq DESC` from local rows. The `seq DESC` tie-break makes the order deterministic and identical on both clients even though `published` is nullable and non-monotonic (§1.3); without it, equal/NULL `published` rows could order differently per platform. |
| E11 | **Feed metadata changes** (rename, pause) | Not part of the article sync; `GET /v1/feeds` stays a wholesale fetch (small). |
| E12 | **Bulk-read sync amplification** — `read-all` (and large multi-select reads) re-stamp `seq` on every affected row | The next pull re-delivers a full `Article` row per affected article, so a `read-all` over the unread corpus can re-download ~the whole corpus once. **Accepted at launch:** it's idempotent, rows are already cached (only `is_read` flips), and it's a one-shot cost per bulk action, not steady-state. If it bites, a future delta-encoding (`{id, is_read}`-only rows for read-state-only changes) is the escape hatch — noted, not built. |
| E13 | **`articles.id` rowid reuse breaking the tombstone PK** — `id` is `INTEGER PRIMARY KEY` without `AUTOINCREMENT`, so the max rowid is reused after deletion | Tombstone PK is `seq` (never reused), not `id` (§3.1). Regression test: delete the max-id article, re-insert (reuses id), delete again → second tombstone must succeed. |
| E14 | **FTS reindex churn from the seq write-back** — every `seq` UPDATE would otherwise re-fire the unscoped FTS `articles_au` trigger | FTS `au` trigger scoped to `AFTER UPDATE OF title, content` (§3.2); since content is immutable it effectively never fires. Test asserts an `is_read` toggle / `seq` write does **not** touch `articles_fts`. |

---

## 6. Deferred follow-up work

Detailed enough to be filed as tickets (each block ≈ one ticket). The first two are **separate follow-ups** that outlive #95; the last two are **in-scope-but-deferred decisions** within the #95 build.

**None of these are filed as tickets yet — by design.** They live here so they aren't forgotten; file the actual tickets when #95 implementation begins. The same goes for *every* deferral elsewhere in this proposal (the tombstone-GC pointer in §3.4) — this section is the single checklist to sweep before declaring #95 done. Test obligations are catalogued separately in the §7 test matrix.

### 6.A New tickets to file (track separately from #95)

**FU-1 — Tombstone GC for the sync log** · server · suggested tier: Deferred
- **What:** add a scheduled job that prunes `deleted_articles` rows older than a bounded horizon (e.g. longest plausible client-offline window — propose 1 year, configurable), and have `/v1/sync` return `{ "full_resync": true }` when a client's `since` is below the oldest surviving tombstone seq.
- **Why:** §3.4 keeps tombstones forever at launch; the table is the one append-only structure in the design and grows without bound (~24 bytes/row, but unbounded over years). This caps it.
- **Becomes relevant when:** the tombstone table grows large, or before any long-lived/multi-year deployment is a concern. Not needed at #95 launch.
- **Acceptance:** GC job prunes tombstones past the horizon; a sync with a too-old cursor returns `full_resync` and the client clears + re-backfills; test seeds an old cursor and asserts the resync path; tombstones within the horizon are never pruned.
- **Depends on:** #95 landed (tombstone table + `/v1/sync` exist).

**FU-2 — Offline read-state mutation queue** · shared + clients · suggested tier: Tier 3 / Deferred
- **What:** queue local `is_read` changes made while offline and flush them to the server on reconnect; guard the sync-apply path so an incoming pull does **not** overwrite an un-acked local change (per-id "pending mutation" set).
- **Why:** §4.3 — today read-state `PUT`s are synchronous, so an offline mark-as-read is lost or errors. The local mirror makes local-first reads natural, but a pull could clobber an offline change before it's synced.
- **Becomes relevant when:** robust offline use becomes a product goal.
- **Acceptance:** marking read/unread offline persists locally and `PUT`s on reconnect; a sync pull arriving while a local change is un-acked does not revert it (test drives a pending-id guard); queue survives process death.
- **Depends on:** #95 landed (mirror + `SyncEngine` exist).

### 6.B In-scope but deferred decisions within #95

- **Web storage backend — decided 2026-06-27: IndexedDB.** IndexedDB is the only browser API that supports indexed range queries over structured data at scale. localStorage/sessionStorage are string-only, 5 MB-limited, and would require deserializing the entire dataset for every read. The `ArticleStore` contract requires windowed range reads (`published DESC, seq DESC`) and aggregate unread counts without materializing all rows — only IndexedDB's cursor-based iteration and index-backed queries can satisfy this at 20k+ rows. The implementation uses an `articles` object store (keyPath `id`) with a compound index on `[published, seq]` for ordering, plus a `meta` store for cursor persistence. Reactive `Flow` emissions are driven by a version counter that increments on every write (upsert/delete/clear), triggering re-queries on active observers.
- **Full migration from today's view-cache** to the mirror — the ticket explicitly says a migration story is **not required**; a fresh install simply backfills via `since = 0`. Recorded as waived, not forgotten.

---

## 7. Test matrix (must-haves before #95 is "done")

Per CLAUDE.md, every change lands with a named, re-runnable test. The notes scattered above ("add a test") are consolidated here as the checklist. Server tests live in [db_tests.rs](../../server/src/db_tests.rs) / the API handler tests; shared-logic tests in `shared/commonTest`.

| ID | What it asserts | Maps to | Where |
|---|---|---|---|
| T1 | `seq` is unique and monotonic across inserts, `is_read` updates, and deletes under interleaved writers | E2, §3.2 | server |
| T2 | Feed-delete **cascade** writes one tombstone per cascaded article row (trigger fires per row, app code untouched) | E1, §1.4 | server |
| T3 | Retention purge `DELETE` writes tombstones the same way | §1.4 | server |
| T4 | `recursive_triggers` is OFF and the `au` seq trigger does not re-fire itself | E3, §1.5 | server |
| T5 | **Rowid-reuse tombstone:** delete max-id article → re-insert (reuses id) → delete again succeeds (no PK conflict) | E13, §3.1 | server |
| T6 | **FTS not touched on non-text update:** an `is_read` toggle / `seq` write produces no `articles_fts` mutation; a `title`/`content` change still reindexes | E14, §3.2 | server |
| T7 | Pagination never splits a seq: with > `limit` candidates, `cursor` lands on a fully-delivered seq and `has_more=true`; the union of both streams is delivered exactly once | E6, §3.3 | server |
| T8 | `limit` defaults to 500 and clamps at 2000; `since=0` omits tombstones; backfill drains to `has_more=false` | E5, §3.3 | server |
| T9 | `since > sync_counter.value` ⇒ `full_resync` response | E8, §3.4 | server |
| T10 | Migration sets `seq = id` over a seeded pre-#95 DB and sets the counter to `MAX(id)`; every subsequently-stamped `seq` exceeds every backfilled one | E9, §3.1 | server |
| T11 | `SyncEngine` loop: upsert-then-delete apply order, cursor advance + persistence, `has_more` follow, `full_resync` clears the store and re-backfills | §4.1, §4.3 | shared |
| T12 | Local badge (`observeUnreadCount`) equals the unread rows visible through the same filter as the windowed list, all-tab and per-feed (badge == list by construction); the list reads come from `observePage`, never a whole-corpus load | §4.0, §4.4, §4.5 | shared |
| T13 | **Insert-path write amplification:** a bulk feed-fetch insert stays within an acceptable bound with the seq triggers active (per-row write-back + counter bump); measured on CI, not locally | §3.2 | server |
| T14 | **Backfill concurrent with live writes:** a row delivered in an early page and deleted before a later page arrives as a tombstone later (net deleted); an insert during paging is picked up by its seq; the cursor never skips or double-counts a seq across the page boundary | E7, §3.3 | server |
| T15 | **Removed route, kept method:** with `/v1/articles/unread-count` deleted, the stats handler still returns its unread count via `get_total_unread_count` (route removal didn't break the kept DB method) | §3.5 | server |
