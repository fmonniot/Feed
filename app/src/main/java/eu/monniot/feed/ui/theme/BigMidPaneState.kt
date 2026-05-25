package eu.monniot.feed.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.FeedUiItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Big mid-pane state — fills the article-list + reader area when no content can show.
 *
 * Shared by all four happy-path empty states and hard-failure screens.
 * The mono detail block present on web is omitted on Android per spec.
 *
 * @param eyebrow  Monospace uppercase label — error code or semantic label.
 * @param title    Serif 28 headline. One sentence, ending with a period.
 * @param body     Serif italic body. Two sentences max.
 * @param primary  Optional (label, onClick) for the primary action.
 * @param secondary Optional (label, onClick) for the secondary action.
 * @param hint     Optional supporting note shown below the buttons.
 * @param modifier Applied to the outermost Box — use to constrain height.
 */
@Composable
fun BigMidPaneState(
    eyebrow: String,
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(40.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            // Eyebrow
            Text(
                text = eyebrow,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.14.sp,
                color = PaperInk3,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Title
            Text(
                text = title,
                fontFamily = SourceSerif4,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = (28 * 1.15).sp,
                letterSpacing = (-0.02).em,
                color = PaperInk,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Body
            Text(
                text = body,
                fontFamily = SourceSerif4,
                fontStyle = FontStyle.Italic,
                fontSize = 15.5.sp,
                lineHeight = (15.5 * 1.55).sp,
                color = PaperInk2,
                modifier = Modifier.padding(bottom = 26.dp),
            )

            // Action buttons
            if (primary != null || secondary != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = if (hint != null) 22.dp else 0.dp),
                ) {
                    if (primary != null) {
                        val (label, onClick) = primary
                        Text(
                            text = label,
                            fontFamily = IbmPlexSans,
                            fontSize = 12.5.sp,
                            color = PaperPanel,
                            modifier = Modifier
                                .background(PaperInk, RoundedCornerShape(4.dp))
                                .clickable(onClick = onClick)
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }

                    if (secondary != null) {
                        val (label, onClick) = secondary
                        Text(
                            text = label,
                            fontFamily = IbmPlexSans,
                            fontSize = 12.5.sp,
                            color = PaperInk2,
                            modifier = Modifier
                                .border(1.dp, PaperBorder, RoundedCornerShape(4.dp))
                                .background(PaperPanel, RoundedCornerShape(4.dp))
                                .clickable(onClick = onClick)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // Hint
            if (hint != null) {
                Text(
                    text = hint,
                    fontFamily = IbmPlexSans,
                    fontSize = 11.5.sp,
                    color = PaperInk3,
                )
            }
        }
    }
}

// ── Happy-path convenience helpers ────────────────────────────────────────────

/** "Pick something to read." — shown in the empty reader pane. */
@Composable
fun BigMidPaneSelectAnArticle(modifier: Modifier = Modifier) = BigMidPaneState(
    eyebrow = "SELECT",
    title = "Pick something to read.",
    body = "Choose an article from the list on the left.",
    modifier = modifier,
)

/** "Nothing here yet." — shown when a feed or filter has no articles. */
@Composable
fun BigMidPaneNothingHereYet(modifier: Modifier = Modifier) = BigMidPaneState(
    eyebrow = "EMPTY",
    title = "Nothing here yet.",
    body = "New articles will appear here as they arrive.",
    modifier = modifier,
)

/** "You're all caught up." — shown when all articles have been read. */
@Composable
fun BigMidPaneCaughtUp(modifier: Modifier = Modifier) = BigMidPaneState(
    eyebrow = "INBOX ZERO",
    title = "You're all caught up.",
    body = "Check back later, or browse older articles in your feeds.",
    modifier = modifier,
)

/** "Welcome to Feed." — shown on first run before any feeds are added. */
@Composable
fun BigMidPaneFirstRun(modifier: Modifier = Modifier) = BigMidPaneState(
    eyebrow = "WELCOME",
    title = "Welcome to Feed.",
    body = "Add your first feed to get started.",
    modifier = modifier,
)

// ── Error-state helpers ───────────────────────────────────────────────────────

/**
 * ERR-7: Dead feed — ≥14 consecutive HTTP 410 Gone responses.
 *
 * @param feed The dead [FeedUiItem].
 * @param onUnsubscribe Called when the user taps "Unsubscribe".
 * @param onKeepWatching Called when the user taps "Keep watching".
 */
@Composable
fun BigMidPaneDeadFeed(
    feed: FeedUiItem,
    onUnsubscribe: () -> Unit,
    onKeepWatching: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFailureDate = feed.first410At?.let { epochSeconds ->
        val instant = Instant.ofEpochSecond(epochSeconds)
        DateTimeFormatter.ISO_LOCAL_DATE.format(instant.atZone(ZoneId.systemDefault()))
    } ?: "unknown"
    BigMidPaneState(
        eyebrow = "ERR · HTTP 410 GONE",
        title = "\"${feed.displayTitle}\" is gone.",
        body = "This feed has returned HTTP 410 Gone 14 or more times. Your cached articles are still readable.",
        primary = "Unsubscribe" to onUnsubscribe,
        secondary = "Keep watching" to onKeepWatching,
        hint = "First failure: $firstFailureDate",
        modifier = modifier,
    )
}
