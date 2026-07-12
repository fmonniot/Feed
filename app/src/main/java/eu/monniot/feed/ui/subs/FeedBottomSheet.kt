package eu.monniot.feed.ui.subs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.monniot.feed.ui.theme.ButtonSize
import eu.monniot.feed.ui.theme.IbmPlexSans
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.SourceSerif4
import eu.monniot.feed.ui.theme.tokens

// ---------------------------------------------------------------------------
// FeedBottomSheet — shared chrome for every bottom-sheet flow (#124), plus the
// SheetRadioRow / SheetTextField primitives every sheet flow is built from.
// ---------------------------------------------------------------------------

/**
 * Shared shell for every bottom-sheet flow (#124): Add feed, Move to
 * category…, Rename feed, Fetch interval, New category, Rename category,
 * Delete category. Built as a full-screen custom [Dialog] (like the existing
 * [AddFeedDialog]/[AddFeedSheet] was before #124) rather than Material3's
 * `ModalBottomSheet`, so the exact VISUAL_SPEC chrome (scrim, grab handle, top
 * corner radius, button row) is fully under our control and stays inside the
 * normal semantics tree for Robolectric.
 */
@Composable
internal fun FeedBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    primaryLabel: String? = null,
    primaryEnabled: Boolean = true,
    primaryDanger: Boolean = false,
    onPrimaryClick: () -> Unit = {},
    secondaryLabel: String = "Cancel",
    secondaryEnabled: Boolean = true,
    testTagSuffix: String = "",
    primaryTestTag: String = "sheet_primary$testTagSuffix",
    secondaryTestTag: String = "sheet_cancel$testTagSuffix",
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFeedColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x52141928))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .testTag("sheet_scrim$testTagSuffix"),
        ) {
            // Cap the sheet at a fraction of the available screen height so a
            // long radio list (MoveToCategorySheet / DeleteCategorySheet with
            // many categories) can never push the grab handle and title off
            // the top of the screen. Material3's ModalBottomSheet does this
            // for free; this is a hand-rolled Dialog, so it needs the same
            // guard by hand (#124 review).
            val maxSheetHeight = maxHeight * 0.85f
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}, // absorb taps so they don't fall through to the scrim
                    )
                    .background(colors.bg, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .border(1.dp, colors.borderStrong, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .padding(top = 10.dp, bottom = 30.dp)
                    .testTag("sheet$testTagSuffix"),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.border),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = title,
                    fontFamily = SourceSerif4,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.015).em,
                    color = colors.ink,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp),
                )
                // Only the content slot scrolls (title and button row stay
                // pinned) — weight(fill = false) lets it shrink to its own
                // size when short, but caps it at the remaining space (and
                // makes it scrollable) when the radio list is long.
                //
                // This testTag is distinct from the outer "sheet$testTagSuffix"
                // on purpose: that outer node's .clickable() merges descendant
                // semantics upward, which gives performScrollToNode inaccurate
                // viewport bounds. Tests that need to scroll within a sheet's
                // content (e.g. a long category list) must target this tag,
                // with useUnmergedTree = true, instead of the outer sheet tag.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .testTag("sheet_content$testTagSuffix"),
                ) {
                    content()
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Same ButtonSize.Medium tier (40dp min height) as every other
                // dialog-action pair in the app (Rename/Delete/OK/Cancel) — see
                // the ButtonSize note on the old AddFeedDialog this replaced.
                val dialogActionTokens = ButtonSize.Medium.tokens()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(
                        text = secondaryLabel,
                        fontFamily = IbmPlexSans,
                        fontSize = dialogActionTokens.fontSize,
                        lineHeight = dialogActionTokens.fontSize * 1.2f,
                        color = if (secondaryEnabled) colors.ink2 else colors.ink2.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = dialogActionTokens.minHeight)
                            .border(1.dp, colors.border, RoundedCornerShape(4.dp))
                            .background(colors.panel, RoundedCornerShape(4.dp))
                            .clickable(enabled = secondaryEnabled, onClick = onDismiss)
                            .padding(dialogActionTokens.contentPadding)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .testTag(secondaryTestTag),
                    )
                    if (primaryLabel != null) {
                        Text(
                            text = primaryLabel,
                            fontFamily = IbmPlexSans,
                            fontSize = dialogActionTokens.fontSize,
                            lineHeight = dialogActionTokens.fontSize * 1.2f,
                            color = if (primaryDanger) colors.danger else colors.panel,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = dialogActionTokens.minHeight)
                                .then(
                                    if (primaryDanger) {
                                        Modifier
                                            .border(1.dp, colors.danger, RoundedCornerShape(4.dp))
                                            .background(colors.panel, RoundedCornerShape(4.dp))
                                    } else {
                                        Modifier.background(
                                            if (primaryEnabled) colors.ink else colors.ink.copy(alpha = 0.4f),
                                            RoundedCornerShape(4.dp),
                                        )
                                    },
                                )
                                .clickable(enabled = primaryEnabled, onClick = onPrimaryClick)
                                .padding(dialogActionTokens.contentPadding)
                                .wrapContentHeight(Alignment.CenterVertically)
                                .testTag(primaryTestTag),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A radio-selectable row inside a "selection sheet" (Move / Delete-reassign /
 * Fetch interval). Either a [trailingNote] (e.g. "current", "default") or a
 * checkmark (via [showCheckmark], for the immediate-select Fetch interval
 * sheet) can trail the label — the two sheets' selection styles differ, so
 * only one is shown at a time.
 */
@Composable
internal fun SheetRadioRow(
    label: String,
    active: Boolean,
    trailingNote: String? = null,
    showCheckmark: Boolean = false,
    checkmarkTestTag: String? = null,
    onClick: () -> Unit,
    testTag: String,
) {
    val colors = LocalFeedColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(1.dp, if (active) colors.accent else colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Box(modifier = Modifier.size(9.dp).background(colors.accent, CircleShape))
            }
        }
        Text(
            text = label,
            fontFamily = SourceSerif4,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            color = if (active) colors.accent else colors.ink,
            modifier = Modifier.weight(1f),
        )
        if (trailingNote != null) {
            Text(
                text = trailingNote,
                fontFamily = SourceSerif4,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                color = colors.ink3,
            )
        }
        if (showCheckmark && active) {
            Text(
                text = "✓",
                fontWeight = FontWeight.Bold,
                color = colors.accent,
                modifier = if (checkmarkTestTag != null) Modifier.testTag(checkmarkTestTag) else Modifier,
            )
        }
    }
}

/** A single-line text input styled for the bottom sheets (category/feed names, URLs). */
@Composable
internal fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    testTag: String,
) {
    val colors = LocalFeedColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.panel)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(4.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, fontFamily = IbmPlexSans, fontSize = 15.sp, color = colors.ink3)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = IbmPlexSans, fontSize = 15.sp, color = colors.ink),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier.fillMaxWidth().testTag(testTag),
        )
    }
}
