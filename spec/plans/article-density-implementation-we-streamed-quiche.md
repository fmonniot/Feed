Date: 2026-05-19 09:31 PDT

# Article Density — Spec + Thumbnail Implementation

## Context

The density setting (Compact / Regular / Comfy) has been implemented for padding, title size, and excerpt visibility in both the web ([ArticleList.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt)) and Android ([ArticleRow.kt](../../app/src/main/java/eu/monniot/feed/ui/feed/ArticleRow.kt)) clients. What remains:

1. **FEATURES.md** has no FEED-group scenarios describing what the article list looks like at each density — only SET-4 (which tests changing the setting, not the resulting layout).
2. **Comfy thumbnail is missing.** The prototype (`spec/prototype/prototypes/editorial.jsx` `EdArticleList`, line 254) shows a 64×64 striped placeholder alongside the excerpt in Comfy mode. Neither client renders it. SET-4 expected: "`comfy`: thumbnails visible" — this is the outstanding half that keeps SET-4 at `⚠`.

The thumbnail is a **design placeholder** (striped gradient derived from `feedHue`) — no real image URL is needed. The `ArticleItem` model already carries `feedHue: Int`.

---

## Part 1 — FEATURES.md additions

File: [spec/FEATURES.md](../FEATURES.md)

### New scenarios (insert after FEED-9)

| ID | Platforms | Setup | Steps | Expected | Status |
|---|---|---|---|---|---|
| FEED-10 | both | Density = `compact`; at least one article with non-blank excerpt | Open article list | Each row uses compact padding (10/18 web, 12/22 android); title is 15px/16sp; excerpt and thumbnail are absent. | ✓ |
| FEED-11 | both | Density = `comfy`; at least one article with non-blank excerpt | Open article list | Each row uses comfy padding (20/22); title is 17px/18sp; a 64×64 (web) / 56×56 (android) hue-colored thumbnail appears to the left of the excerpt, both in the same flex row. | ✗ (this plan) |

### Status updates

- **SET-4**: flip from `⚠ web (#31)` to `✓` once thumbnail lands on both platforms.

---

## Part 2 — Web (`ArticleList.kt`)

File: [web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt)

Replace the current excerpt block (lines 341–356) with three-way density logic:

```kotlin
if (density != Density.Compact) {
    if (density == Density.Comfy) {
        // Comfy: 64×64 thumbnail + excerpt side-by-side
        div {
            attributes["style"] = "display: flex; gap: 12px; align-items: flex-start; margin-top: 4px;"
            div {
                // striped placeholder matching prototype EdThumb(hue, 64, 64)
                attributes["data-feed-thumb"] = item.feedHue.toString()
                attributes["style"] = buildString {
                    val hA = "oklch(0.90 0.03 ${item.feedHue})"
                    val hB = "oklch(0.85 0.04 ${item.feedHue})"
                    append("width: 64px; height: 64px; flex-shrink: 0;")
                    append("border-radius: 2px;")
                    append("border: 1px solid var(--feed-border);")
                    append("background: repeating-linear-gradient(135deg, $hA 0 6px, $hB 6px 12px);")
                }
            }
            if (item.excerpt.isNotBlank()) {
                div {
                    attributes["style"] = buildString {
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12px;")
                        append("color: var(--feed-ink2);")
                        append("line-height: 1.45; flex: 1;")
                        append("overflow: hidden;")
                        append("display: -webkit-box;")
                        append("-webkit-line-clamp: 2;")
                        append("-webkit-box-orient: vertical;")
                    }
                    +item.excerpt
                }
            }
        }
    } else if (item.excerpt.isNotBlank()) {
        // Regular: excerpt only (existing behaviour)
        div {
            attributes["style"] = buildString {
                append("font-family: var(--feed-font-sans);")
                append("font-size: 12px;")
                append("color: var(--feed-ink2);")
                append("line-height: 1.4;")
                append("overflow: hidden;")
                append("display: -webkit-box;")
                append("-webkit-line-clamp: 2;")
                append("-webkit-box-orient: vertical;")
            }
            +item.excerpt
        }
    }
}
```

**Web test** — add to [web/src/jsTest/kotlin/eu/monniot/feed/web/ui/feed/ArticleListSelectionTest.kt](../../web/src/jsTest/kotlin/eu/monniot/feed/web/ui/feed/ArticleListSelectionTest.kt) after `rowContainsExcerptInComfyDensity`:

```kotlin
@Test
fun rowShowsThumbnailInComfyDensity() {
    val host = renderFixtureRows(density = Density.Comfy)
    val thumbs = host.querySelectorAll("[data-feed-thumb]")
    assertTrue("Comfy density must render a thumbnail per row", thumbs.length > 0)
}
```

---

## Part 3 — Android

### New component: `FeedThumbnail.kt`

File: [app/src/main/java/eu/monniot/feed/ui/components/FeedThumbnail.kt](../../app/src/main/java/eu/monniot/feed/ui/components/FeedThumbnail.kt)

Pattern mirrors `FeedDot.kt` (same package, same `feedHue()` util). Uses `Canvas` with a `rotate` transform to draw alternating diagonal bands:

```kotlin
package eu.monniot.feed.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.ui.theme.LocalFeedColors
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize

// Matches prototype EdThumb: two hue-derived tones, diagonal stripe pattern
// oklch(0.90 0.03 hue) ≈ HSL(hue, 10%, 93%)
// oklch(0.85 0.04 hue) ≈ HSL(hue, 13%, 88%)
@Composable
fun FeedThumbnail(
    feedId: Int,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val hue = feedHue(feedId).toFloat()
    val stripeA = Color.hsl(hue = hue, saturation = 0.10f, lightness = 0.93f)
    val stripeB = Color.hsl(hue = hue, saturation = 0.13f, lightness = 0.88f)
    val borderColor = LocalFeedColors.current.border

    Canvas(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(2.dp))
            .semantics { contentDescription = "Article thumbnail" },
    ) {
        // Base fill
        drawRect(color = stripeA)
        // Diagonal bands at 45° (CSS 135deg convention)
        val stripeWidthPx = 6.dp.toPx()
        val diagonal = this.size.width + this.size.height
        rotate(degrees = -45f, pivot = center) {
            var x = center.x - diagonal
            var useB = false
            while (x < center.x + diagonal) {
                if (useB) {
                    drawRect(
                        color = stripeB,
                        topLeft = Offset(x, -diagonal),
                        size = GeoSize(stripeWidthPx, diagonal * 3),
                    )
                }
                x += stripeWidthPx
                useB = !useB
            }
        }
        // 1px border
        drawRect(color = borderColor, style = Stroke(width = 1.dp.toPx()))
    }
}
```

### Update `ArticleRow.kt`

File: [app/src/main/java/eu/monniot/feed/ui/feed/ArticleRow.kt](../../app/src/main/java/eu/monniot/feed/ui/feed/ArticleRow.kt)

Replace the excerpt block (lines 181–190) with three-way density logic:

```kotlin
if (density != UserDensity.Compact) {
    Spacer(modifier = Modifier.height(2.dp))
    if (density == UserDensity.Comfy) {
        // Comfy: 56×56 thumbnail + excerpt side-by-side
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            FeedThumbnail(feedId = article.feedId, size = 56.dp)
            if (article.excerpt.isNotBlank()) {
                Text(
                    text = article.excerpt,
                    style = typography.listExcerpt.copy(color = colors.ink2),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else if (article.excerpt.isNotBlank()) {
        // Regular: excerpt only
        Text(
            text = article.excerpt,
            style = typography.listExcerpt.copy(color = colors.ink2),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

Add `import eu.monniot.feed.ui.components.FeedThumbnail` to the file's imports.

**Android test** — add to [app/src/test/java/eu/monniot/feed/ui/feed/ArticleRowTest.kt](../../app/src/test/java/eu/monniot/feed/ui/feed/ArticleRowTest.kt):

```kotlin
@Test
fun comfyDensityShowsThumbnailAndExcerpt() {
    composeTestRule.setContent {
        FeedTheme {
            ArticleRow(article = unreadArticle, density = Density.Comfy, onClick = {})
        }
    }
    composeTestRule.onNodeWithContentDescription("Article thumbnail").assertExists()
    composeTestRule.onNodeWithText(unreadArticle.excerpt).assertExists()
}

@Test
fun compactDensityHidesThumbnailAndExcerpt() {
    composeTestRule.setContent {
        FeedTheme {
            ArticleRow(article = unreadArticle, density = Density.Compact, onClick = {})
        }
    }
    composeTestRule.onAllNodesWithContentDescription("Article thumbnail").assertCountEquals(0)
    composeTestRule.onAllNodesWithText(unreadArticle.excerpt).assertCountEquals(0)
}
```

---

## Critical files

| File | Change |
|---|---|
| `spec/FEATURES.md` | Add FEED-10, FEED-11; flip SET-4 to ✓ |
| `web/.../feed/ArticleList.kt` | Three-way density in `articleRow()` — thumbnail for Comfy |
| `web/.../feed/ArticleListSelectionTest.kt` | Add `rowShowsThumbnailInComfyDensity` test |
| `app/.../components/FeedThumbnail.kt` | New composable (new file) |
| `app/.../feed/ArticleRow.kt` | Three-way density — FeedThumbnail for Comfy |
| `app/.../feed/ArticleRowTest.kt` | Add comfy thumbnail + compact exclusion tests |

---

## Verification

```sh
# Web
./gradlew :web:jsTest   # expect 113+ tests pass (112 existing + new thumbnail test)

# Android
./gradlew :app:testDebugUnitTest  # expect 52+ tests pass (50 existing + 2 new)

# All
( cd server && cargo test ) && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
```

Manual: open the app (web + Android) with density set to Comfy — each article row should show a feed-hue-colored striped square to the left of the excerpt text. Compact rows must show neither.
