package eu.monniot.feed.ui.subs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.FeedErrorAction
import eu.monniot.feed.shared.FeedErrorTone
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.shared.util.relativeTimeFromEpochSeconds
import eu.monniot.feed.ui.theme.ButtonSize
import eu.monniot.feed.ui.theme.FeedTone
import eu.monniot.feed.ui.theme.IbmPlexSans
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography
import eu.monniot.feed.ui.theme.SourceSerif4
import eu.monniot.feed.ui.theme.ToneErrBd
import eu.monniot.feed.ui.theme.ToneErrBg
import eu.monniot.feed.ui.theme.ToneErrFg
import eu.monniot.feed.ui.theme.TonePill
import eu.monniot.feed.ui.theme.ToneWarnBd
import eu.monniot.feed.ui.theme.ToneWarnBg
import eu.monniot.feed.ui.theme.ToneWarnFg
import eu.monniot.feed.ui.theme.tokens

// ---------------------------------------------------------------------------
// FeedErrorSummaryBanner, CategoryHeaderRow, FeedRow — the grouped feed list's
// row-level components (#124 split: SubscriptionsScreen.kt had grown to host
// the screen, every sheet flow, and these rows in one file).
// ---------------------------------------------------------------------------

/**
 * Summary banner above the search box — shows error/warn count and last-checked time.
 */
@Composable
internal fun FeedErrorSummaryBanner(
    summary: eu.monniot.feed.shared.FeedErrorSummary,
    modifier: Modifier = Modifier,
) {
    val isError = summary.tone == FeedErrorTone.Error
    val bgColor = if (isError) ToneErrBg else ToneWarnBg
    val bdColor = if (isError) ToneErrBd else ToneWarnBd
    val fgColor = if (isError) ToneErrFg else ToneWarnFg

    // Count chip text
    val chipText = if (summary.errorCount > 0) {
        if (summary.errorCount == 1) "1 error" else "${summary.errorCount} errors"
    } else {
        if (summary.warnCount == 1) "1 warning" else "${summary.warnCount} warnings"
    }

    // Message text
    val messageParts = mutableListOf<String>()
    if (summary.errorCount > 0) messageParts += "${summary.errorCount} failing"
    if (summary.warnCount > 0) messageParts += "${summary.warnCount} warning${if (summary.warnCount != 1) "s" else ""}"
    val lastChecked = summary.lastCheckedAt?.let { relativeTimeFromEpochSeconds(it) }
    val messageText = buildString {
        append(messageParts.joinToString(" · ")) // middle dot
        if (lastChecked != null) append(" — last checked $lastChecked") // em dash
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(1.dp, bdColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Count chip
        Text(
            text = chipText.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.14.sp,
            color = fgColor,
            modifier = Modifier
                .border(1.dp, bdColor, RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("error_count_chip"),
        )

        // Message
        Text(
            text = messageText,
            fontFamily = IbmPlexSans,
            fontSize = 13.sp,
            color = fgColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("error_summary_message"),
        )
    }
}

/**
 * Uppercase category header (SUBS-1). Non-locked categories (i.e. not
 * "Uncategorized") carry a trailing `⋯` opening Rename… / Delete category…
 * (SUBS-14/15). "Uncategorized" is permanent and locked — no `⋯`.
 */
@Composable
internal fun CategoryHeaderRow(
    group: CategoryGroup,
    onRenameRequested: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 14.dp, top = 20.dp, bottom = 6.dp)
            .testTag("group_header_${group.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.name.uppercase(),
            style = typography.folderLabel.copy(
                color = colors.ink3,
                letterSpacing = 0.1.sp,
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${group.feeds.size}",
            fontFamily = IbmPlexSans,
            fontSize = 10.5.sp,
            color = colors.ink3,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (!group.locked) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    // Category-specific description (mirrors FeedOverflowMenu's
                    // "Feed options") so TalkBack announces which category's
                    // menu this is instead of the bare "⋯" glyph.
                    modifier = Modifier
                        .size(28.dp)
                        .semantics { contentDescription = "${group.name} options" }
                        .testTag("category_overflow_${group.id}"),
                ) {
                    Text(text = "⋯", color = colors.ink3, fontSize = 16.sp)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename…") },
                        onClick = { showMenu = false; onRenameRequested() },
                        modifier = Modifier.testTag("category_menu_rename_${group.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text("Delete category…", color = colors.danger) },
                        onClick = { showMenu = false; onDeleteRequested() },
                        modifier = Modifier.testTag("category_menu_delete_${group.id}"),
                    )
                }
            }
        }
    }
}

/**
 * A single feed row inside a category group.
 *
 * Healthy layout: 34x34 letter avatar | name + URL | unread count | overflow menu.
 * Broken layout: dimmed avatar | name + URL + tone badge | time-since + chevron | overflow menu (#94).
 * Tapping a broken row toggles the inline accordion; tapping the overflow menu does not (the
 * menu's IconButton consumes the tap before it reaches the row's clickable).
 */
@Composable
internal fun FeedRow(
    feed: FeedUiItem,
    /** Live unread count (#9) — see [SubscriptionsScreenContent]'s perFeedUnreadCounts param. */
    liveUnreadCount: Int = feed.unreadCount,
    errorDetail: eu.monniot.feed.shared.FeedErrorDetail?,
    isAccordionExpanded: Boolean,
    onRename: () -> Unit,
    /** #124: opens the "Move to category…" bottom sheet (SUBS-10), hoisted to the screen level. */
    onOpenMoveSheet: () -> Unit,
    onSetInterval: () -> Unit,
    onTogglePaused: () -> Unit,
    onDelete: () -> Unit,
    onToggleAccordion: () -> Unit,
    onRefreshFeed: () -> Unit,
    onFixUrl: (newUrl: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onViewRaw: (() -> Unit)?,
    onUnsubscribe: () -> Unit,
    onMarkFeedAsRead: () -> Unit = {},
    /** BUG-56: "Change URL" from the overflow menu — available for healthy feeds too. */
    onChangeUrl: () -> Unit = {},
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current
    val borderColor = colors.border

    var showMenu by remember { mutableStateOf(false) }

    val isBroken = errorDetail != null

    // Avatar colors: HSL approximation of oklch(0.85 0.05 hue) bg, oklch(0.35 0.08 hue) fg
    val hue = feedHue(feed.id).toFloat()
    val avatarBg = Color.hsl(hue = hue, saturation = 0.25f, lightness = 0.88f)
    val avatarFg = Color.hsl(hue = hue, saturation = 0.35f, lightness = 0.35f)

    // First letter of display title for the avatar
    val avatarLetter = feed.displayTitle.take(1).uppercase()

    // Broken feeds have dimmed avatars (0.6 opacity)
    val avatarAlpha = if (isBroken) 0.6f else 1f

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .then(
                    if (isBroken) {
                        Modifier
                            .testTag("broken_feed_row_${feed.id}")
                            .clickable(onClick = onToggleAccordion)
                    } else Modifier
                )
                .drawBehind {
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 34x34 letter avatar with 4dp radius
            Box(
                modifier = Modifier
                    .alpha(avatarAlpha)
                    .size(34.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(avatarBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = avatarLetter,
                    style = typography.listTitle.copy(
                        fontFamily = SourceSerif4,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = avatarFg,
                    ),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Name + URL + optional tone badge (fills remaining width)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = feed.displayTitle,
                        style = typography.listTitle.copy(
                            fontFamily = SourceSerif4,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = colors.ink,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("feed_name_${feed.id}"),
                    )
                    if (isBroken) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val tone = if (errorDetail.tone == FeedErrorTone.Error) FeedTone.Err else FeedTone.Warn
                        TonePill(
                            tone = tone,
                            label = errorDetail.badgeLabel,
                        )
                    }
                }
                Text(
                    text = feed.url,
                    style = typography.listExcerpt.copy(fontSize = 11.sp, color = colors.ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isBroken) {
                // Right gutter for broken feeds: time-since + chevron.
                val toneFg = if (errorDetail.tone == FeedErrorTone.Error) ToneErrFg else ToneWarnFg
                Column(horizontalAlignment = Alignment.End) {
                    val lastAttempt = feed.lastAttempt
                    if (lastAttempt != null) {
                        Text(
                            text = relativeTimeFromEpochSeconds(lastAttempt),
                            fontFamily = IbmPlexSans,
                            fontSize = 11.sp,
                            color = toneFg,
                            modifier = Modifier.testTag("time_since_${feed.id}"),
                        )
                    }
                    Text(
                        text = if (isAccordionExpanded) "▲" else "▼", // ▲ / ▼
                        fontSize = 10.sp,
                        color = colors.ink3,
                        modifier = Modifier.testTag("chevron_${feed.id}"),
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))
            } else {
                // Healthy feed: unread count
                Text(
                    text = "$liveUnreadCount",
                    style = typography.time.copy(fontSize = 11.sp, color = colors.ink3),
                    modifier = Modifier.testTag("unread_count_${feed.id}"),
                )
            }

            // Single call site shared by both branches so broken and healthy
            // rows can never drift apart again (#94).
            FeedOverflowMenu(
                feed = feed,
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it },
                onRefreshFeed = onRefreshFeed,
                onRename = onRename,
                onOpenMoveSheet = onOpenMoveSheet,
                onSetInterval = onSetInterval,
                onTogglePaused = onTogglePaused,
                onDelete = onDelete,
                onMarkFeedAsRead = onMarkFeedAsRead,
                onChangeUrl = onChangeUrl,
            )
        }

        // Inline accordion for broken feeds
        if (isBroken && isAccordionExpanded) {
            FeedErrorAccordion(
                errorDetail = errorDetail,
                feedUrl = feed.url,
                onRefreshFeed = onRefreshFeed,
                onFixUrl = onFixUrl,
                onViewRaw = onViewRaw,
                onUnsubscribe = onUnsubscribe,
                modifier = Modifier.testTag("accordion_${feed.id}"),
            )
        }
    }
}

/**
 * Overflow (⋯) menu shared by healthy and broken feed rows (#94).
 *
 * The [IconButton] has its own click handling, so tapping it does not
 * propagate to an enclosing row's `clickable` (e.g. the broken-row accordion
 * toggle) — Compose's clickable modifier consumes the tap before it reaches
 * an ancestor.
 */
@Composable
private fun FeedOverflowMenu(
    feed: FeedUiItem,
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onRefreshFeed: () -> Unit,
    onRename: () -> Unit,
    onOpenMoveSheet: () -> Unit,
    onSetInterval: () -> Unit,
    onTogglePaused: () -> Unit,
    onDelete: () -> Unit,
    onMarkFeedAsRead: () -> Unit = {},
    /** BUG-56: "Change URL" — available regardless of the feed's health status. */
    onChangeUrl: () -> Unit = {},
) {
    val colors = LocalFeedColors.current

    Box {
        IconButton(
            onClick = { onShowMenuChange(true) },
            modifier = Modifier.size(32.dp).testTag("overflow_menu_${feed.id}"),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Feed options", tint = colors.ink3)
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { onShowMenuChange(false) }) {
            DropdownMenuItem(
                text = { Text("Refresh this feed") },
                onClick = { onShowMenuChange(false); onRefreshFeed() },
                modifier = Modifier.testTag("menu_refresh_feed_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text("Mark all as read") },
                onClick = { onShowMenuChange(false); onMarkFeedAsRead() },
                modifier = Modifier.testTag("menu_mark_feed_read_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text("Rename…") },
                onClick = { onShowMenuChange(false); onRename() },
                modifier = Modifier.testTag("menu_rename_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text("Change URL") },
                onClick = { onShowMenuChange(false); onChangeUrl() },
                modifier = Modifier.testTag("menu_change_url_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text("Move to category…") },
                onClick = { onShowMenuChange(false); onOpenMoveSheet() },
                modifier = Modifier.testTag("menu_move_category_${feed.id}"),
            )
            DropdownMenuItem(
                text = { Text("Fetch interval…") },
                onClick = { onShowMenuChange(false); onSetInterval() },
                modifier = Modifier.testTag("menu_fetch_interval_${feed.id}"),
            )
            // BUG-63 part 2: on a cache-seeded (stale) row feed.isPaused is a snapshot from
            // whenever the store was last written, not a live read. Both this item's label
            // and the direction of the toggle SubscriptionsScreen derives from it
            // (!feed.isPaused) come off that snapshot, so a feed paused from another device
            // would read "Pause updates" and send is_paused=true for a feed that is already
            // paused. Disabled rather than guessed; toggling needs the network anyway, and
            // the item comes back live the moment a loadFeeds() succeeds.
            DropdownMenuItem(
                text = { Text(if (!feed.stale && feed.isPaused) "Resume updates" else "Pause updates") },
                enabled = !feed.stale,
                onClick = { onShowMenuChange(false); onTogglePaused() },
                modifier = Modifier.testTag("menu_pause_resume_${feed.id}"),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Unsubscribe", color = colors.danger) },
                onClick = { onShowMenuChange(false); onDelete() },
                modifier = Modifier.testTag("menu_delete_${feed.id}"),
            )
        }
    }
}

/**
 * Inline accordion with mono diagnostic block, human explanation, and action buttons.
 */
@Composable
private fun FeedErrorAccordion(
    errorDetail: eu.monniot.feed.shared.FeedErrorDetail,
    feedUrl: String,
    onRefreshFeed: () -> Unit,
    onFixUrl: (newUrl: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onViewRaw: (() -> Unit)?,
    onUnsubscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val toneFg = if (errorDetail.tone == FeedErrorTone.Error) ToneErrFg else ToneWarnFg

    // Fix URL editor state
    var showFixUrlEditor by remember { mutableStateOf(false) }
    var fixUrlText by remember(feedUrl) { mutableStateOf(feedUrl) }
    var fixUrlError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(3.dp))
            .drawBehind {
                // 3px left border in tone foreground
                drawRect(
                    color = toneFg,
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                )
            }
            .padding(12.dp),
    ) {
        // Mono diagnostic block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(colors.bg)
                .border(1.dp, colors.border, RoundedCornerShape(3.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("diagnostic_block"),
        ) {
            errorDetail.diagnosticLines.forEach { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = (11 * 1.7).sp,
                    color = colors.ink2,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Human explanation
        Text(
            text = errorDetail.explanation,
            fontFamily = IbmPlexSans,
            fontSize = 12.5.sp,
            lineHeight = (12.5 * 1.55).sp,
            color = colors.ink2,
            modifier = Modifier.testTag("explanation_text"),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Fix URL inline editor
        if (showFixUrlEditor) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                OutlinedTextField(
                    value = fixUrlText,
                    onValueChange = { fixUrlText = it; fixUrlError = null },
                    label = { Text("Feed URL") },
                    singleLine = true,
                    isError = fixUrlError != null,
                    modifier = Modifier.fillMaxWidth().testTag("fix_url_input"),
                )
                if (fixUrlError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = fixUrlError!!,
                        fontFamily = IbmPlexSans,
                        fontSize = 11.sp,
                        color = ToneErrFg,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        label = "Save",
                        onClick = {
                            if (fixUrlText.isNotBlank()) {
                                onFixUrl(
                                    fixUrlText.trim(),
                                    { showFixUrlEditor = false },
                                    { error -> fixUrlError = error },
                                )
                            }
                        },
                        modifier = Modifier.testTag("fix_url_save"),
                    )
                    ActionButton(
                        label = "Cancel",
                        onClick = {
                            showFixUrlEditor = false
                            fixUrlText = feedUrl
                            fixUrlError = null
                        },
                        modifier = Modifier.testTag("fix_url_cancel"),
                    )
                }
            }
        }

        // Action buttons row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            errorDetail.actions.forEach { action ->
                when (action) {
                    FeedErrorAction.RetryNow -> ActionButton(
                        label = "Retry now",
                        onClick = onRefreshFeed,
                        modifier = Modifier.testTag("action_retry_now"),
                    )
                    FeedErrorAction.RetryOnce -> ActionButton(
                        label = "Retry once",
                        onClick = onRefreshFeed,
                        modifier = Modifier.testTag("action_retry_once"),
                    )
                    FeedErrorAction.FixUrl -> ActionButton(
                        label = "Fix URL…",
                        onClick = { showFixUrlEditor = !showFixUrlEditor },
                        modifier = Modifier.testTag("action_fix_url"),
                    )
                    FeedErrorAction.ViewRaw -> if (onViewRaw != null) {
                        ActionButton(
                            label = "View raw ↗",
                            onClick = onViewRaw,
                            modifier = Modifier.testTag("action_view_raw"),
                        )
                    }
                    FeedErrorAction.Unsubscribe -> ActionButton(
                        label = "Unsubscribe",
                        onClick = onUnsubscribe,
                        isDanger = true,
                        modifier = Modifier.testTag("action_unsubscribe"),
                    )
                }
            }
        }
    }
}

/**
 * Action button used inside the accordion — flat bordered pill.
 * Sized via [ButtonSize.Small] (same tier as the reader top-bar buttons).
 */
@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    isDanger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFeedColors.current
    val borderCol = if (isDanger) colors.danger else colors.border
    val textCol = if (isDanger) colors.danger else colors.ink2
    val sizeTokens = ButtonSize.Small.tokens()

    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        // heightIn passes the tier min as a non-zero incoming constraint,
        // replacing Material3's internal 40dp floor — see the ButtonSize note.
        modifier = modifier
            .heightIn(min = sizeTokens.minHeight)
            .border(1.dp, borderCol, RoundedCornerShape(4.dp)),
        contentPadding = sizeTokens.contentPadding,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = colors.panel,
            contentColor = textCol,
        ),
    ) {
        Text(
            text = label,
            fontFamily = IbmPlexSans,
            fontSize = sizeTokens.fontSize,
            color = textCol,
        )
    }
}
