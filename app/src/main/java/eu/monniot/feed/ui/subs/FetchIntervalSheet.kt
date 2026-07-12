package eu.monniot.feed.ui.subs

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.ui.theme.IbmPlexSans
import eu.monniot.feed.ui.theme.LocalFeedColors

// ---------------------------------------------------------------------------
// Fetch interval — bottom sheet (SUBS-11)
// ---------------------------------------------------------------------------

/** Preset fetch-interval choices for the sheet. */
internal val FETCH_INTERVAL_PRESETS = listOf(
    15 to "Every 15 minutes",
    30 to "Every 30 minutes",
    60 to "Every 1 hour",
    360 to "Every 6 hours",
    1440 to "Every 24 hours",
)

@Composable
internal fun FetchIntervalSheet(
    feed: FeedUiItem,
    onConfirm: (intervalMinutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFeedColors.current
    FeedBottomSheet(
        title = "Fetch Interval",
        onDismiss = onDismiss,
        testTagSuffix = "_fetch_interval",
        secondaryTestTag = "fetch_interval_cancel",
    ) {
        Text(
            text = "How often should “${feed.displayTitle}” be checked for new articles?",
            fontFamily = IbmPlexSans,
            fontSize = 13.sp,
            color = colors.ink2,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
        )
        FETCH_INTERVAL_PRESETS.forEach { (minutes, label) ->
            SheetRadioRow(
                label = label,
                active = feed.fetchIntervalMinutes == minutes,
                showCheckmark = true,
                checkmarkTestTag = "interval_selected_$minutes",
                onClick = {
                    onConfirm(minutes)
                    onDismiss()
                },
                testTag = "interval_option_$minutes",
            )
        }
    }
}
