package eu.monniot.feed.ui.subs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.AddFeedError
import eu.monniot.feed.ui.theme.FeedTone
import eu.monniot.feed.ui.theme.IbmPlexSans
import eu.monniot.feed.ui.theme.InlineFormError
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.ToneErrBd
import eu.monniot.feed.ui.theme.ToneWarnBd
import eu.monniot.feed.ui.theme.ToneWarnFg

// ---------------------------------------------------------------------------
// Add feed — bottom sheet (SUBS-2)
// ---------------------------------------------------------------------------

/**
 * Add-feed bottom sheet. A new feed always lands in "Uncategorized" — the
 * sheet notes this so the user knows to re-file it afterward from the feed's
 * ⋯ menu (SUBS-2).
 */
@Composable
internal fun AddFeedSheet(
    isLoading: Boolean,
    error: AddFeedError?,
    onConfirm: (url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val colors = LocalFeedColors.current

    // ERR-13: block submit when the URL matches an existing subscription
    val isDuplicate = error is AddFeedError.Duplicate
    // Highlight the field with the tone border for both err and warn tones
    val fieldBorderColor = when (error) {
        null -> colors.borderStrong
        is AddFeedError.Duplicate -> ToneWarnBd
        else -> ToneErrBd
    }

    FeedBottomSheet(
        title = "Add Feed",
        onDismiss = { if (!isLoading) onDismiss() },
        primaryLabel = "Add",
        primaryEnabled = url.isNotBlank() && !isLoading && !isDuplicate,
        onPrimaryClick = { onConfirm(url) },
        // Reviewer follow-up: Cancel was already functionally guarded
        // (onDismiss no-ops while isLoading) but kept its normal visual —
        // dim it to match, same as the primary button already does.
        secondaryEnabled = !isLoading,
        testTagSuffix = "_add_feed",
        primaryTestTag = "add_feed_confirm",
        secondaryTestTag = "add_feed_cancel",
    ) {
        Column(modifier = Modifier.padding(horizontal = 22.dp)) {
            // Field label
            Text(
                text = "FEED URL",
                fontFamily = IbmPlexSans,
                fontSize = 11.sp,
                letterSpacing = 0.14.em,
                color = colors.ink3,
            )
            Spacer(Modifier.height(6.dp))

            // Input row with bottom border, switches to tone colour on error
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                enabled = !isLoading,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontSize = 16.sp,
                    color = if (!isLoading) colors.ink else colors.muted,
                ),
                cursorBrush = SolidColor(colors.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("add_feed_url_input"),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.padding(vertical = 4.dp)) {
                        if (url.isEmpty()) {
                            Text(
                                text = "https://example.com/feed.xml",
                                fontFamily = IbmPlexSans,
                                fontSize = 16.sp,
                                color = colors.ink3,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            HorizontalDivider(color = fieldBorderColor, thickness = 1.dp)

            if (error != null) {
                Spacer(modifier = Modifier.height(10.dp))
                when (error) {
                    is AddFeedError.ParseFail -> InlineFormError(
                        tone = FeedTone.Err,
                        message = "This URL didn't return a valid feed. Paste the feed URL directly (e.g. example.com/rss/feed.xml), not the site's homepage.",
                    )
                    is AddFeedError.Duplicate -> {
                        val folderClause = if (error.folderName != null) " — it's in the ${error.folderName} folder" else ""
                        val annotated = buildAnnotatedString {
                            append("You're already subscribed to ")
                            withStyle(SpanStyle(
                                textDecoration = TextDecoration.Underline,
                                color = ToneWarnFg,
                            )) {
                                append(error.feedName)
                            }
                            append("$folderClause. Open it instead, or change the URL above.")
                        }
                        InlineFormError(tone = FeedTone.Warn, message = annotated)
                    }
                    is AddFeedError.Generic -> InlineFormError(
                        tone = FeedTone.Err,
                        message = error.message,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // SUBS-2: the feed always lands in Uncategorized; the user re-files
            // it afterward from the feed's ⋯ menu (Move to category…).
            Text(
                text = "Added to “Uncategorized” — move it to another category afterward from the feed's ⋯ menu.",
                fontFamily = IbmPlexSans,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                lineHeight = (12 * 1.4).sp,
                color = colors.ink3,
                modifier = Modifier.testTag("add_feed_uncategorized_note"),
            )
        }
    }
}
