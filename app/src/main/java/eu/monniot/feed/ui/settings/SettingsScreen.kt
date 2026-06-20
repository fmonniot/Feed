package eu.monniot.feed.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.monniot.feed.BuildConfig
import eu.monniot.feed.FeedViewModel
import androidx.compose.ui.tooling.preview.Preview
import eu.monniot.feed.shared.api.OpmlFeedResult
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.shared.data.KeepArticles
import eu.monniot.feed.shared.data.RefreshInterval
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography

// ---------------------------------------------------------------------------
// SettingsScreen — wired to ViewModel
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(
    viewModel: FeedViewModel,
    onServerUrlClick: () -> Unit,
    onLogout: () -> Unit,
) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serverVersion by viewModel.serverVersion.collectAsStateWithLifecycle()
    val opmlImportStatus by viewModel.opmlImportStatus.collectAsStateWithLifecycle()
    val opmlImportFailures by viewModel.opmlImportFailures.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadServerVersion()
        viewModel.loadRetention()
    }

    // Clear stale import state when the user navigates away from the Settings screen.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearOpmlImportStatus()
            viewModel.clearOpmlImportFailures()
        }
    }

    val context = LocalContext.current
    val opmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) viewModel.importOpmlFromUri(context.contentResolver, uri)
    }

    SettingsScreenContent(
        prefs = prefs,
        serverUrl = serverUrl,
        serverVersion = serverVersion,
        opmlImportStatus = opmlImportStatus,
        opmlImportFailures = opmlImportFailures,
        onUpdateFontSize = { viewModel.updateFontSize(it) },
        onUpdateDensity = { viewModel.updateDensity(it) },
        onUpdateRefreshInterval = { viewModel.updateRefreshInterval(it) },
        onUpdateKeepArticles = { viewModel.updateKeepArticles(it) },
        onServerUrlClick = onServerUrlClick,
        // "text/xml" restricts the picker to XML/OPML files; if a user's exported OPML
        // has no MIME registration, they can always switch to "All files" in the picker.
        onChooseOpml = { opmlLauncher.launch("text/xml") },
        onDismissOpmlResult = {
            viewModel.clearOpmlImportStatus()
            viewModel.clearOpmlImportFailures()
        },
        onLogout = onLogout,
    )
}

// ---------------------------------------------------------------------------
// Version hint helper (internal for testability)
// ---------------------------------------------------------------------------

internal fun buildVersionHint(serverVersion: String?, clientVersion: String = BuildConfig.VERSION_NAME): String =
    if (serverVersion != null) "Client v$clientVersion · Server v$serverVersion"
    else "Client v$clientVersion · Server unreachable"

// ---------------------------------------------------------------------------
// SettingsScreenContent — stateless, used by tests
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreenContent(
    prefs: UserPrefs.Snapshot,
    serverUrl: String = "",
    serverVersion: String? = null,
    opmlImportStatus: String? = null,
    opmlImportFailures: List<OpmlFeedResult> = emptyList(),
    onUpdateFontSize: (Int) -> Unit = {},
    onUpdateDensity: (Density) -> Unit = {},
    onUpdateRefreshInterval: (RefreshInterval) -> Unit = {},
    onUpdateKeepArticles: (KeepArticles) -> Unit = {},
    onServerUrlClick: () -> Unit = {},
    onChooseOpml: () -> Unit = {},
    onDismissOpmlResult: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    val colors = LocalFeedColors.current

    if (opmlImportFailures.isNotEmpty()) {
        val summary = opmlImportStatus ?: "Import complete."
        AlertDialog(
            modifier = Modifier.testTag("opml_result_dialog"),
            onDismissRequest = onDismissOpmlResult,
            title = { Text("Import complete") },
            text = {
                Column {
                    Text(summary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Failed feeds:",
                        style = LocalFeedTypography.current.listSectionTitle.copy(fontSize = 12.sp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        items(opmlImportFailures) { feed ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = feed.title.ifBlank { feed.url },
                                    style = LocalFeedTypography.current.listTitle.copy(fontSize = 13.sp),
                                )
                                val feedError = feed.error
                                if (!feedError.isNullOrBlank()) {
                                    Text(
                                        text = feedError,
                                        style = LocalFeedTypography.current.listExcerpt.copy(
                                            fontSize = 11.sp,
                                            color = colors.ink3,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissOpmlResult) { Text("OK") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // ---- Settings list ----
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // === Reading section ===
            item { SectionHeader(label = "Reading") }
            item {
                SettingsSegmentedRow(
                    label = "Font size",
                    hint = "Applies to article body — live.",
                    options = listOf(
                        14 to "14", 16 to "16", 18 to "18",
                        20 to "20", 22 to "22", 24 to "24",
                    ),
                    selected = prefs.fontSize,
                    onSelect = onUpdateFontSize,
                    segControlTag = "font_size_seg",
                    testTag = "row_font_size",
                )
            }
            item {
                SettingsSegmentedRow(
                    label = "Density",
                    hint = "Compact hides excerpts. Comfy shows thumbnails.",
                    options = listOf(
                        Density.Compact to "Compact",
                        Density.Regular to "Regular",
                        Density.Comfy to "Comfy",
                    ),
                    selected = prefs.density,
                    onSelect = onUpdateDensity,
                    segControlTag = "density_seg",
                    testTag = "row_density",
                )
            }

            // === Sync section ===
            item { SectionHeader(label = "Sync") }
            item {
                SettingsSegmentedRow(
                    label = "Refresh interval",
                    hint = "Client-side auto-poll cadence.",
                    options = listOf(
                        RefreshInterval.Min15 to "15m",
                        RefreshInterval.Hour1 to "1h",
                        RefreshInterval.Hour6 to "6h",
                        RefreshInterval.Manual to "Manual",
                    ),
                    selected = prefs.refreshInterval,
                    onSelect = onUpdateRefreshInterval,
                    segControlTag = "refresh_seg",
                    testTag = "row_refresh_interval",
                )
            }
            item {
                SettingsSegmentedRow(
                    label = "Keep articles",
                    hint = "Retention window for the server sweep.",
                    options = listOf(
                        KeepArticles.Days30 to "30d",
                        KeepArticles.Days90 to "90d",
                        KeepArticles.Year1 to "1y",
                        KeepArticles.Forever to "∞",
                    ),
                    selected = prefs.keepArticles,
                    onSelect = onUpdateKeepArticles,
                    segControlTag = "keep_articles_seg",
                    testTag = "row_keep_articles",
                )
            }
            item {
                SettingsRow(
                    label = "Server URL",
                    value = serverUrl.take(40),
                    testTag = "row_server_url",
                    onClick = onServerUrlClick,
                )
            }

            // === Account section ===
            item { SectionHeader(label = "Account") }
            item {
                SettingsRow(
                    label = "Import OPML",
                    value = "Choose…",
                    hint = if (opmlImportFailures.isNotEmpty()) null
                        else opmlImportStatus ?: "Upload a backup or another reader's export.",
                    testTag = "row_import_opml",
                    onClick = onChooseOpml,
                )
            }
            item {
                SettingsRow(
                    label = "About Feed",
                    value = "›",
                    hint = buildVersionHint(serverVersion),
                    testTag = "row_about",
                    showChevron = false,
                    onClick = { /* About — future */ },
                )
            }
            item {
                SettingsRow(
                    label = "Logout",
                    value = "›",
                    testTag = "row_logout",
                    showChevron = false,
                    labelColor = colors.accent,
                    onClick = onLogout,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Internal composables
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(label: String) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current

    Text(
        text = label.uppercase(),
        style = typography.folderLabel.copy(
            color = colors.ink3,
            letterSpacing = 0.1.sp,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    hint: String? = null,
    testTag: String,
    onClick: () -> Unit,
    showChevron: Boolean = true,
    labelColor: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border
    val effectiveLabelColor = labelColor ?: colors.ink

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .clickable(onClick = onClick)
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 22.dp, vertical = 14.dp)
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = typography.settingsLabel.copy(color = effectiveLabelColor),
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = typography.settingsHint.copy(fontSize = 12.sp, color = colors.ink3),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    style = typography.settingsHint.copy(
                        fontSize = 13.sp,
                        color = labelColor ?: colors.ink3,
                    ),
                )
            }
            if (showChevron) {
                Text(
                    text = " ›",
                    style = typography.settingsHint.copy(fontSize = 16.sp, color = colors.muted),
                )
            }
        }
    }
}

@Composable
private fun <T> SettingsSegmentedRow(
    label: String,
    hint: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    segControlTag: String,
    testTag: String,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = typography.settingsLabel.copy(color = colors.ink),
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = typography.settingsHint.copy(fontSize = 12.sp, color = colors.ink3),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        SettingsSegmentedControl(
            options = options,
            selected = selected,
            onSelect = onSelect,
            controlTag = segControlTag,
        )
    }
}

@Composable
private fun <T> SettingsSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    controlTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val shape = RoundedCornerShape(4.dp)

    Row(
        modifier = modifier
            .border(1.dp, colors.border, shape)
            .clip(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .background(if (active) colors.ink else Color.Transparent)
                    .clickable(enabled = !active) { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("${controlTag}_${label}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = typography.settingsHint.copy(
                        fontSize = 11.5.sp,
                        color = if (active) colors.panel else colors.ink2,
                        fontFeatureSettings = "\"tnum\"",
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Display name extensions (kept for potential future use)
// ---------------------------------------------------------------------------

private val RefreshInterval.displayName: String
    get() = when (this) {
        RefreshInterval.Min15 -> "15m"
        RefreshInterval.Hour1 -> "1h"
        RefreshInterval.Hour6 -> "6h"
        RefreshInterval.Manual -> "Manual"
    }

private val KeepArticles.displayName: String
    get() = when (this) {
        KeepArticles.Days30 -> "30 days"
        KeepArticles.Days90 -> "90 days"
        KeepArticles.Year1 -> "1 year"
        KeepArticles.Forever -> "Forever"
    }

@Preview(showBackground = true, name = "Settings – defaults")
@Composable
private fun SettingsScreenPreview() {
    FeedTheme {
        SettingsScreenContent(prefs = UserPrefs.Snapshot())
    }
}

@Preview(showBackground = true, name = "Settings – custom values")
@Composable
private fun SettingsScreenCustomPreview() {
    FeedTheme {
        SettingsScreenContent(
            prefs = UserPrefs.Snapshot(
                fontSize = 22,
                density = Density.Compact,
                refreshInterval = RefreshInterval.Hour6,
                keepArticles = KeepArticles.Year1,
            ),
            serverUrl = "http://192.168.1.10:3000/",
        )
    }
}
