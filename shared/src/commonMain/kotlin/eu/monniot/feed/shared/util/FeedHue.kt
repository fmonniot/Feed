package eu.monniot.feed.shared.util

/**
 * Deterministic hue (0–359) computed from a feed id.
 * Identical result for the same feed id across sidebar, list, and reader meta.
 *
 * ## History (ticket #36)
 *
 * The original Phase 1 implementation was `(feedId.hashCode() ushr 1) % 360`. Since
 * `Int.hashCode()` is the identity function (`this`), that reduced to `(feedId ushr 1) % 360`
 * — for non-negative ids, just `feedId / 2 % 360`. Feed ids are Postgres/SQLite
 * auto-increment primary keys, i.e. small sequential integers, so this mapping guaranteed
 * that *every consecutive pair* of feed ids (2k, 2k+1) collided on the exact same hue,
 * for any id range. That's a much higher collision rate than the "birthday paradox"
 * bound you'd expect from a well-mixed hash (~42% chance of *any* collision at N=20
 * feeds) — it was closer to a ~50% guaranteed collision on neighboring ids at any N ≥ 2,
 * which is exactly the clash SUBS-5 observed.
 *
 * The fix: run the id through a splitmix64-style bit-mixer (the same finalizer used by
 * `java.util.SplittableRandom` / Kotlin's `Random`) before reducing mod 360. This spreads
 * sequential ids uniformly across the hue wheel instead of preserving their linear order,
 * so the observed collision rate now matches the expected birthday-paradox bound for a
 * uniform hash over 360 buckets: 200 uniform draws are expected to land on
 * 360 × (1 − (359/360)²⁰⁰) ≈ 153.6 distinct buckets, i.e. ≈46 colliding entries out of
 * 200. The actual count for ids 1..200 with this mixer is exactly 46, with zero
 * adjacent-id collisions (empirically verified in [FeedHueTest]) — critically, no longer
 * a *guaranteed* per-neighbor clash.
 */
fun feedHue(feedId: Int): Int {
    return ((mix64(feedId.toLong()) ushr 1) % 360).toInt()
}

/**
 * SplitMix64 finalizer (public-domain, by Sebastiano Vigna / Guy Steele). A cheap,
 * dependency-free integer mixer with excellent avalanche properties: single-bit
 * differences in the input flip roughly half the output bits. Used here to turn a small
 * sequential id into a well-distributed 64-bit value before bucketing into hues.
 */
internal fun mix64(input: Long): Long {
    var z = input
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xbf58476d1ce4e5b9
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94d049bb133111eb
    return z xor (z ushr 31)
}
