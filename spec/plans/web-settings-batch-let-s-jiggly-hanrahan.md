# Plan: Web Settings batch (#30, #31, #32)

Date: 2026-05-17 19:00 PDT

## Context

Three tickets that all live on the web Settings screen, sized to land in a single session.

- **#30** — Reader font-size control is missing. `UserPrefs.fontSize` (14–24 in 6 steps) is
  persisted and already applied by the reader pane, but the web Settings screen has no
  segmented control exposing it to the user.
- **#31** — Density control is missing. `UserPrefs.density` (Compact/Regular/Comfy) is already
  persisted and already applied by `ArticleList.kt` for row padding and excerpt visibility, but
  the web Settings screen has no control, so users are stuck at the default (Regular). Also,
  the article row's title size is hardcoded at 17px regardless of density; per VISUAL_SPEC the
  Compact title should be 15px.
- **#32** — Server URL row should be removed. The web HTTP client is wired to
  `window.location.origin` at boot (see `Main.kt:24`); the `serverUrlStore`-backed input in
  Settings has no production effect and is listed as broken in SET-6.

## Critical files

| File | Role |
|---|---|
| `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SettingsScreen.kt` | Settings UI — primary change target for all three tickets |
| `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt` | Article row rendering — title size fix for #31 |
| `web/src/jsTest/kotlin/eu/monniot/feed/web/ui/feed/ArticleListSelectionTest.kt` | Existing density tests (add Comfy case + compact title-size case) |
| `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt` | Provides `updateFontSize(Int)`, `updateDensity(Density)` — reuse as-is |
| `shared/src/commonMain/kotlin/eu/monniot/feed/shared/data/UserPrefs.kt` | Provides `Density` enum and font-size range — reuse as-is |
| `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/Segmented.kt` | Reusable `segmented()` DSL + `wireSegmentedClicks()` — reuse as-is |

## Implementation steps

### 1. `SettingsScreen.kt` — add font-size and density rows, remove Server URL

**A. Add missing imports** at the top of the file:

```kotlin
import eu.monniot.feed.shared.data.Density
```

**B. Reading section — new rows** in `settingsContent()`.

Insert two new `settingsRow` calls inside the Reading `settingsGroup`, after the existing
"Reader theme" row and before "Default sort":

```kotlin
settingsRow(label = "Reader font size") {
    segmented(
        options = listOf("14" to "14", "16" to "16", "18" to "18",
                         "20" to "20", "22" to "22", "24" to "24"),
        current = prefs.fontSize.toString(),
        name = "reader-font-size",
        onSelect = {},
    )
}

settingsRow(label = "Density") {
    segmented(
        options = listOf(
            "Compact" to "Compact",
            "Regular" to "Regular",
            "Comfy"   to "Comfy",
        ),
        current = prefs.density.name,
        name = "density",
        onSelect = {},
    )
}
```

Update `isFirst`/`isLast` markers so the group reads:
1. Mark as read on scroll (`isFirst = true`)
2. Reader theme (no marker)
3. Reader font size (new, no marker)
4. Density (new, no marker)
5. Default sort (`isLast = true`)

**C. Wire the new controls** in `renderSettingsContent()`, alongside the existing
`wireSegmentedClicks` calls:

```kotlin
wireSegmentedClicks("reader-font-size", content) { value ->
    viewModel.updateFontSize(value.toInt())
}
wireSegmentedClicks("density", content) { value ->
    val d = when (value) {
        "Compact" -> Density.Compact
        "Comfy"   -> Density.Comfy
        else      -> Density.Regular
    }
    viewModel.updateDensity(d)
}
```

**D. Remove Server URL row (#32)**.

In `settingsContent()`, delete the entire `// Server URL` div block (lines 387–445).

In `renderSettings()`, delete the `viewModel.serverUrlError.collect` observer block
(lines 108–114).

In `renderSettingsContent()`, delete:
- The server URL save button listener (lines 185–188)
- The `GlobalScope.launch { val url = viewModel.serverUrl.value ... }` block (lines 196–200)

Remove the now-unused private constants `SETTINGS_URL_INPUT_ID` and `SETTINGS_URL_ERROR_ID`.

---

### 2. `ArticleList.kt` — Compact title size fix (#31)

In `articleRow()`, replace the hardcoded title `font-size: 17px` with a density-aware value:

```kotlin
val titleSize = if (density == Density.Compact) "15px" else "17px"
// then in the style:
append("font-size: $titleSize;")
```

---

### 3. Tests

#### 3a. New file: `web/src/jsTest/kotlin/eu/monniot/feed/web/ui/SettingsSegmentedControlsTest.kt`

Six tests following the pattern in `SegmentedControlTest.kt` (render a standalone segmented
control into a host div, inspect the resulting DOM):

| Test | Asserts |
|---|---|
| `fontSizeControlHasCorrectOptions` | Renders 6 buttons (14/16/18/20/22/24) |
| `fontSizeControlReflectsCurrentValue` | When `current = "18"`, button "18" has `aria-pressed=true` |
| `fontSizeControlClickFiresCallback` | Clicking inactive "22" button fires callback with value `"22"` |
| `densityControlHasCorrectOptions` | Renders 3 buttons (Compact/Regular/Comfy) |
| `densityControlReflectsCurrentValue` | When `current = "Regular"`, "Regular" button has `aria-pressed=true` |
| `densityControlClickFiresCallback` | Clicking inactive "Comfy" fires callback with value `"Comfy"` |

These tests verify the data wired into the segmented controls matches what Settings renders and
what `wireSegmentedClicks` will call back with.

#### 3b. `ArticleListSelectionTest.kt` — two new tests

```kotlin
@Test fun rowContainsExcerptInComfyDensity() { ... }
// Comfy density row shows excerpt (parallel to rowContainsExcerptInRegularDensity)

@Test fun rowTitleIsSmallerInCompactDensity() { ... }
// Compact row style contains "15px"; Regular row style contains "17px"
```

---

## Verification

```sh
./gradlew :web:jsTest
```

Baseline: 11 tests pass. After this change: 11 + 6 (new settings tests) + 2 (new
ArticleList density tests) = **19 tests pass, 0 fail**.

Manual smoke check (start dev server with `./gradlew :web:jsBrowserDevelopmentRun`):
1. Navigate to Settings — Reading section shows "Reader font size" (6 segments, 18 active by
   default) and "Density" (3 segments, Regular active by default). No "Server URL" row.
2. Change font size to 22, open an article in the reader — body text renders at 22px.
3. Change density to Compact, return to article list — rows are tighter, excerpts hidden,
   titles smaller.
4. Change density to Comfy — rows are wider, excerpts visible.
5. Account section still shows "Signed in as" and logout; no Server URL input.
