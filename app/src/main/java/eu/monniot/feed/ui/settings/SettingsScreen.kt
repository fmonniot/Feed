package eu.monniot.feed.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.shared.data.DefaultSort
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.shared.data.KeepArticles
import eu.monniot.feed.shared.data.ReaderTheme
import eu.monniot.feed.shared.data.RefreshInterval
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography

// ---------------------------------------------------------------------------
// SettingsScreen — wired to ViewModel
// ---------------------------------------------------------------------------

/**
 * "Settings" tab screen — sectioned list of user preferences.
 *
 * Sections: Reading, Sync, Account.
 * Each row is tappable and opens a [ModalBottomSheet] picker.
 * Values are persisted via [FeedViewModel.updateXxx] setters.
 */
@Composable
fun SettingsScreen(
    viewModel: FeedViewModel,
    onServerUrlClick: () -> Unit,
    onLogout: () -> Unit,
) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()

    SettingsScreenContent(
        prefs = prefs,
        serverUrl = serverUrl,
        onUpdateFontSize = { viewModel.updateFontSize(it) },
        onUpdateDensity = { viewModel.updateDensity(it) },
        onUpdateMarkAsReadOnScroll = { viewModel.updateMarkAsReadOnScroll(it) },
        onUpdateReaderTheme = { viewModel.updateReaderTheme(it) },
        onUpdateDefaultSort = { viewModel.updateDefaultSort(it) },
        onUpdateRefreshInterval = { viewModel.updateRefreshInterval(it) },
        onUpdateKeepArticles = { viewModel.updateKeepArticles(it) },
        onServerUrlClick = onServerUrlClick,
        onLogout = onLogout,
    )
}

// ---------------------------------------------------------------------------
// SettingsScreenContent — stateless, used by tests
// ---------------------------------------------------------------------------

/**
 * Stateless settings screen.
 *
 * Accepts all preference values and callbacks directly so Robolectric tests
 * can compose it without a live [FeedViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    prefs: UserPrefs.Snapshot,
    serverUrl: String = "",
    onUpdateFontSize: (Int) -> Unit = {},
    onUpdateDensity: (Density) -> Unit = {},
    onUpdateMarkAsReadOnScroll: (Boolean) -> Unit = {},
    onUpdateReaderTheme: (ReaderTheme) -> Unit = {},
    onUpdateDefaultSort: (DefaultSort) -> Unit = {},
    onUpdateRefreshInterval: (RefreshInterval) -> Unit = {},
    onUpdateKeepArticles: (KeepArticles) -> Unit = {},
    onServerUrlClick: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

    // Track which picker is currently open
    var activePicker by remember { mutableStateOf<SettingsPicker?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // ---- Header ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .padding(horizontal = 22.dp, vertical = 22.dp)
                .drawBehind {
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
        ) {
            // Large title: serif 30sp/500
            Text(
                text = "Settings",
                style = typography.listSectionTitle.copy(
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.02).sp,
                    lineHeight = (30 * 1.05).sp,
                    color = colors.ink,
                ),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Personal · this device",
                style = typography.listExcerpt.copy(color = colors.ink3, fontSize = 12.sp),
            )
        }

        // ---- Settings list ----
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // === Reading section ===
            item {
                SectionHeader(label = "Reading")
            }
            item {
                SettingsRow(
                    label = "Mark as read on scroll",
                    value = if (prefs.markAsReadOnScroll) "On" else "Off",
                    testTag = "row_mark_as_read",
                    onClick = { activePicker = SettingsPicker.MarkAsReadOnScroll },
                )
            }
            item {
                SettingsRow(
                    label = "Reader theme",
                    value = prefs.readerTheme.displayName,
                    testTag = "row_reader_theme",
                    onClick = { activePicker = SettingsPicker.ReaderTheme },
                )
            }
            item {
                SettingsRow(
                    label = "Default sort",
                    value = prefs.defaultSort.displayName,
                    testTag = "row_default_sort",
                    onClick = { activePicker = SettingsPicker.DefaultSort },
                )
            }
            item {
                SettingsRow(
                    label = "Font size",
                    value = "${prefs.fontSize}sp",
                    testTag = "row_font_size",
                    onClick = { activePicker = SettingsPicker.FontSize },
                )
            }
            item {
                SettingsRow(
                    label = "Density",
                    value = prefs.density.displayName,
                    testTag = "row_density",
                    onClick = { activePicker = SettingsPicker.Density },
                )
            }

            // === Sync section ===
            item {
                SectionHeader(label = "Sync")
            }
            item {
                SettingsRow(
                    label = "Refresh interval",
                    value = prefs.refreshInterval.displayName,
                    testTag = "row_refresh_interval",
                    onClick = { activePicker = SettingsPicker.RefreshInterval },
                )
            }
            item {
                SettingsRow(
                    label = "Keep articles",
                    value = prefs.keepArticles.displayName,
                    testTag = "row_keep_articles",
                    onClick = { activePicker = SettingsPicker.KeepArticles },
                )
            }

            // === Account section ===
            item {
                SectionHeader(label = "Account")
            }
            item {
                SettingsRow(
                    label = "Import OPML",
                    value = "Choose…",
                    testTag = "row_import_opml",
                    onClick = { /* OPML import — future */ },
                )
            }
            item {
                SettingsRow(
                    label = "About Feed",
                    value = "v1.0.0",
                    testTag = "row_about",
                    onClick = { /* About — future */ },
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
            item {
                SettingsRow(
                    label = "Logout",
                    value = "",
                    testTag = "row_logout",
                    showChevron = false,
                    labelColor = colors.accent,
                    onClick = onLogout,
                )
            }
        }
    }

    // ---- Bottom-sheet pickers ----
    activePicker?.let { picker ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { activePicker = null },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp),
            ) {
                Text(
                    text = picker.title,
                    style = typography.settingsLabel.copy(
                        fontWeight = FontWeight.Medium,
                        color = colors.ink,
                    ),
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                when (picker) {
                    SettingsPicker.FontSize -> {
                        listOf(14, 16, 18, 20, 22, 24).forEach { size ->
                            PickerOption(
                                label = "${size}sp",
                                isSelected = prefs.fontSize == size,
                                testTag = "font_size_option_$size",
                                onClick = {
                                    onUpdateFontSize(size)
                                    activePicker = null
                                },
                            )
                        }
                    }
                    SettingsPicker.Density -> {
                        Density.entries.forEach { d ->
                            PickerOption(
                                label = d.displayName,
                                isSelected = prefs.density == d,
                                testTag = "density_option_${d.name}",
                                onClick = {
                                    onUpdateDensity(d)
                                    activePicker = null
                                },
                            )
                        }
                    }
                    SettingsPicker.MarkAsReadOnScroll -> {
                        PickerOption(
                            label = "On",
                            isSelected = prefs.markAsReadOnScroll,
                            testTag = "mark_read_on",
                            onClick = { onUpdateMarkAsReadOnScroll(true); activePicker = null },
                        )
                        PickerOption(
                            label = "Off",
                            isSelected = !prefs.markAsReadOnScroll,
                            testTag = "mark_read_off",
                            onClick = { onUpdateMarkAsReadOnScroll(false); activePicker = null },
                        )
                    }
                    SettingsPicker.ReaderTheme -> {
                        eu.monniot.feed.shared.data.ReaderTheme.entries.forEach { t ->
                            PickerOption(
                                label = t.displayName,
                                isSelected = prefs.readerTheme == t,
                                testTag = "reader_theme_${t.name}",
                                onClick = {
                                    onUpdateReaderTheme(t)
                                    activePicker = null
                                },
                            )
                        }
                    }
                    SettingsPicker.DefaultSort -> {
                        eu.monniot.feed.shared.data.DefaultSort.entries.forEach { s ->
                            PickerOption(
                                label = s.displayName,
                                isSelected = prefs.defaultSort == s,
                                testTag = "default_sort_${s.name}",
                                onClick = {
                                    onUpdateDefaultSort(s)
                                    activePicker = null
                                },
                            )
                        }
                    }
                    SettingsPicker.RefreshInterval -> {
                        RefreshInterval.entries.forEach { r ->
                            PickerOption(
                                label = r.displayName,
                                isSelected = prefs.refreshInterval == r,
                                testTag = "refresh_interval_${r.name}",
                                onClick = {
                                    onUpdateRefreshInterval(r)
                                    activePicker = null
                                },
                            )
                        }
                    }
                    SettingsPicker.KeepArticles -> {
                        KeepArticles.entries.forEach { k ->
                            PickerOption(
                                label = k.displayName,
                                isSelected = prefs.keepArticles == k,
                                testTag = "keep_articles_${k.name}",
                                onClick = {
                                    onUpdateKeepArticles(k)
                                    activePicker = null
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
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
    testTag: String,
    onClick: () -> Unit,
    showChevron: Boolean = true,
    labelColor: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

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
        Text(
            text = label,
            style = typography.settingsLabel.copy(color = labelColor ?: colors.ink),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    style = typography.settingsHint.copy(fontSize = 13.sp, color = colors.ink3),
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
private fun PickerOption(
    label: String,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current

    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = typography.settingsLabel.copy(
                    color = if (isSelected) colors.accent else colors.ink,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                ),
            )
            if (isSelected) {
                Text("✓", style = typography.settingsLabel.copy(color = colors.accent))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Picker enum
// ---------------------------------------------------------------------------

private enum class SettingsPicker(val title: String) {
    FontSize("Font size"),
    Density("Density"),
    MarkAsReadOnScroll("Mark as read on scroll"),
    ReaderTheme("Reader theme"),
    DefaultSort("Default sort"),
    RefreshInterval("Refresh interval"),
    KeepArticles("Keep articles"),
}

// ---------------------------------------------------------------------------
// Display name extensions
// ---------------------------------------------------------------------------

private val Density.displayName: String
    get() = when (this) {
        Density.Compact -> "Compact"
        Density.Regular -> "Regular"
        Density.Comfy -> "Comfy"
    }

private val ReaderTheme.displayName: String
    get() = when (this) {
        ReaderTheme.Paper -> "Paper"
        ReaderTheme.Soft -> "Soft"
        ReaderTheme.Dim -> "Dim"
    }

private val DefaultSort.displayName: String
    get() = when (this) {
        DefaultSort.Newest -> "Newest"
        DefaultSort.Priority -> "Priority"
    }

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
