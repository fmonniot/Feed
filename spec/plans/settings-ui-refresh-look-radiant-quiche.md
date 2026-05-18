Date: 2026-05-17 20:11 PDT

# Settings UI Refresh — Match Prototype

## Context

The prototype in `spec/prototype/prototypes/editorial.jsx` (web) and `editorial-mobile.jsx`
(Android) defines the intended look of the Settings screen. The current implementations
diverge in several ways:

- **Web** still has "Reader theme", "Default sort", and "Signed in as" rows that the prototype
  omits; rows lack hint text; the "About" row is missing; the Logout button is not danger-styled.
- **Android** uses a tap-row → ModalBottomSheet pattern for all preferences, while the prototype
  shows inline segmented controls directly in each row. It also has the Server URL row in the
  wrong section (Account instead of Sync) and missing hint text throughout.

Goal: bring both UIs into alignment with the prototype spec without touching the ViewModel API
or preference persistence layer.

---

## Critical files

| File | Change |
|------|--------|
| `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SettingsScreen.kt` | Remove/reorder rows, add hints, fix Account section |
| `web/src/jsMain/resources/css/tokens.css` | Add `--feed-danger: #a05050` |
| `app/src/main/java/eu/monniot/feed/ui/settings/SettingsScreen.kt` | Replace bottom-sheet pickers with inline segmented rows, restructure sections |
| `app/src/test/java/eu/monniot/feed/ui/settings/SettingsScreenTest.kt` | Update for new UI shape |

Prototype reference (read-only):
- `spec/prototype/prototypes/editorial.jsx` lines 540–617 (`EdSettings`)
- `spec/prototype/prototypes/editorial-mobile.jsx` lines 261–357 (`EdMSettingsScreen`)

---

## Web changes (`SettingsScreen.kt`)

### Reading section — reorder and prune

Target order (matches prototype `EdSettings` lines 578–590):

1. **Reader font size** — hint: `"Applies to the article body. Live-updates the open reader without reload."`
2. **Article-list density** — hint: `"Compact hides excerpts; Comfy shows thumbnails."`
3. **Mark as read on scroll** — hint: `"A row visible for ≥ 1s flips to read."`

Remove the "Reader theme" row, its `wireSegmentedClicks("reader-theme", ...)` handler, and
the "Default sort" row with its handler. Remove `DefaultSort` and `ReaderTheme` imports.

### Sync section — add hints

- **Refresh interval** — hint: `"Client-side auto-poll cadence for the article list."`
- **Keep articles** — hint: `"Retention window. ∞ disables retention."`

### Account section — restructure

Remove the "Signed in as" `div` block (lines 331–354 in the current file).

New row order (matches prototype lines 603–613):

1. **Import OPML** — hint: `"Bring in a backup or export from another reader."` (keep existing button)
2. **About** — hint: `"Client v1.0.0 · Server v0.7.2"`, right side: `—` (em-dash static span)
3. **Logout** — hint: `"Clears the local session and returns to the login screen."`, keep Sign-out button but add `color: var(--feed-danger)` and `border-color: var(--feed-danger)` (prototype uses `ED_C.danger`)

**Add** `--feed-danger: #a05050;` to `web/src/jsMain/resources/css/tokens.css` (it is referenced
in the prototype's `ED_PALETTES` but missing from the production tokens file). Then use
`var(--feed-danger)` for the Logout button border and color, replacing the current `var(--feed-ink2)`.

The `settingsRow` DSL helper already accepts a `hint` parameter — just pass it.

The "About" row can use `settingsRow` with a plain `span` as the `control` block (em-dash text).

---

## Android changes (`SettingsScreen.kt`)

### New composables

**`SettingsSegmentedControl<T>`** (private, inline at bottom of file):

```kotlin
@Composable
private fun <T> SettingsSegmentedControl(
    options: List<Pair<T, String>>,   // (value, label) pairs
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
)
```

Styling (matches mobile prototype `Seg`, lines 263–277):
- Outer: `Row` with `border = 1dp solid colors.border`, `shape = RoundedCornerShape(4.dp)`,
  `clip = true`, `background = colors.panel`
- Each option: `Box` that is `clickable { onSelect(value) }` with:
  - `background = if (active) colors.ink else Color.Transparent`
  - `padding(vertical = 6.dp, horizontal = 10.dp)`
  - `Text` at 11.5sp, `color = if (active) colors.panel else colors.ink2`,
    `fontFeatureSettings = "tnum"` (tabular-nums)

Use `Modifier.border(1.dp, colors.border, RoundedCornerShape(4.dp))` on the outer `Row`
(not background + clip, which avoids nested clipping issues with Compose).

**`SettingsSegmentedRow<T>`** (private):

```kotlin
@Composable
private fun <T> SettingsSegmentedRow(
    label: String,
    hint: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    isLast: Boolean = false,
)
```

Layout:
- Outer `Row`, `fillMaxWidth`, `padding(horizontal = 22.dp, vertical = 12.dp)`,
  bottom border unless `isLast` (same `drawBehind` pattern as `SettingsRow`)
- Left `Column(modifier = Modifier.weight(1f))`:
  - `Text(label, typography.settingsLabel, color = colors.ink)`
  - If hint ≠ null: `Text(hint, typography.settingsHint, color = colors.ink3)` at 12sp with 2dp top margin
- Right: `SettingsSegmentedControl(...)`

**Update `SettingsRow`** to accept an optional `hint` parameter and render it below the label:

```kotlin
private fun SettingsRow(
    label: String,
    value: String,
    hint: String? = null,   // new
    testTag: String,
    onClick: () -> Unit,
    showChevron: Boolean = true,
    labelColor: androidx.compose.ui.graphics.Color? = null,
)
```

### Section restructure

**Reading section** (matches prototype lines 314–326):

```
SettingsSegmentedRow("Reader font size",
    hint = "Applies to article body — live.",
    options = listOf(14 to "14", 16 to "16", 18 to "18", 20 to "20", 22 to "22", 24 to "24"),
    selected = prefs.fontSize,
    onSelect = onUpdateFontSize,
    testTag = "row_font_size")

SettingsSegmentedRow("Article-list density",
    hint = "Compact hides excerpts. Comfy shows thumbnails.",
    options = listOf(Density.Compact to "Compact", Density.Regular to "Regular", Density.Comfy to "Comfy"),
    selected = prefs.density,
    onSelect = onUpdateDensity,
    testTag = "row_density")

SettingsSegmentedRow("Mark as read on scroll",
    hint = "Mark a row read after ≥ 1s visible.",
    options = listOf(true to "On", false to "Off"),
    selected = prefs.markAsReadOnScroll,
    onSelect = onUpdateMarkAsReadOnScroll,
    isLast = true,
    testTag = "row_mark_as_read")
```

Remove "Reader theme" and "Default sort" rows.

**Sync section** (matches prototype lines 330–340):

```
SettingsSegmentedRow("Refresh interval",
    hint = "Client-side auto-poll cadence.",
    options = listOf(RefreshInterval.Min15 to "15m", ...),
    selected = prefs.refreshInterval,
    onSelect = onUpdateRefreshInterval,
    testTag = "row_refresh_interval")

SettingsSegmentedRow("Keep articles",
    hint = "Retention window for the server sweep.",
    options = listOf(KeepArticles.Days30 to "30d", ...),
    selected = prefs.keepArticles,
    onSelect = onUpdateKeepArticles,
    testTag = "row_keep_articles")

SettingsRow("Server URL",
    value = serverUrl.take(40),
    hint = null,
    testTag = "row_server_url",
    onClick = onServerUrlClick,
    isLast = true)
```

**Account section** (matches prototype lines 343–353):

```
SettingsRow("Import OPML",
    value = "Choose…",
    hint = "Upload a backup or another reader's export.",
    testTag = "row_import_opml",
    onClick = { /* OPML import — future */ })

SettingsRow("About Feed",
    value = "›",
    hint = "Client v1.0.0 · Server v0.7.2",
    testTag = "row_about",
    onClick = { /* About — future */ })

SettingsRow("Logout",
    value = "›",
    hint = null,
    testTag = "row_logout",
    showChevron = false,       // chevron replaced by value "›" in accent
    labelColor = colors.accent,
    onClick = onLogout)
```

For Logout, change `value = ""` + `showChevron = false` to `value = "›"` + `showChevron = false`
and let `SettingsRow` render the value text in `labelColor` (accent) as well, not just the label.

### Remove bottom-sheet code

- Delete `SettingsPicker` enum
- Delete `PickerOption` composable
- Delete `activePicker` state and the entire `activePicker?.let { ... }` block
- Remove `@OptIn(ExperimentalMaterial3Api::class)` annotation
- Remove all `ModalBottomSheet`, `rememberModalBottomSheetState` imports
- Remove `onUpdateReaderTheme` and `onUpdateDefaultSort` from `SettingsScreenContent` signature
- In `SettingsScreen` (the wired wrapper), remove those two lambda arguments from the call

Remove now-unused `displayName` extensions: `ReaderTheme.displayName`, `DefaultSort.displayName`.
Keep the rest.

---

## Test changes (`SettingsScreenTest.kt`)

**`settingsRowLabelsAreDisplayed`**: change assertions from `"Reader theme"` / `"Default sort"`
to `"Font size"` / `"Density"`.

**`currentPreferenceValuesAreShownInRows`**: the values are now rendered inside the segmented
control, not as trailing text. Replace `onNodeWithText("Dim")` / `onNodeWithText("Priority")` /
`onNodeWithText("Off")` with assertions that the segmented option text nodes exist
(e.g. `onNodeWithText("Off").assertIsDisplayed()`).

**`changingFontSizePersistsToUserPrefs`**: remove the `onNodeWithTag("row_font_size").performClick()`
/ bottom-sheet open step. Instead tap the segmented button directly using a test tag
`"font_size_seg_22"` (added in `SettingsSegmentedControl` via `Modifier.testTag`).

**`changingDensityInvokesDensityCallback`**: same pattern — tap `"density_seg_Compact"` directly.

**`settingsHasAllExpectedGroups`**: remove `assertEquals(ReaderTheme.Paper, prefs.readerTheme)` and
`assertEquals(DefaultSort.Newest, prefs.defaultSort)` (still valid assertions for the prefs object
itself; keep them or remove based on whether they still test anything meaningful after the UI change).

`defaultPrefs()` helper in the test: remove `readerTheme` and `defaultSort` parameters (or keep them
as they still exist on `UserPrefs.Snapshot`; the preference data model is not changed).

---

## Verification

**Web (manual — UI change):**
1. `./gradlew :web:jsBrowserDevelopmentRun` and open the app.
2. Navigate to Settings. Confirm three sections: Reading (3 rows with hints), Sync (2 rows with hints),
   Account (3 rows: Import OPML, About —, Sign out in danger color).
3. Confirm no "Reader theme", "Default sort", or "Signed in as" rows.
4. Change font size; confirm the segmented control updates reactively.

**Web tests:**
```sh
./gradlew :web:jsTest
```
11 tests should pass (no changes to test files required).

**Android (manual — UI change):**
1. Build and run the app on an emulator or device.
2. Navigate to Settings tab. Confirm inline segmented controls appear directly in each row.
3. Tap a segmented option (e.g. change font size to 22) — confirm preference persists on restart.

**Android tests:**
```sh
./gradlew :app:testDebugUnitTest
```
50 tests should pass. The updated `SettingsScreenTest` should exercise the new segmented control
test tags directly.
