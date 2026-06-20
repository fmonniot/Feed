package eu.monniot.feed.ui.feed

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Test tag applied to the scrollbar indicator container, allowing tests to
 * verify the scroll indicator is present in the composition.
 */
const val ScrollIndicatorTestTag = "ScrollIndicator"

/**
 * Draws a thin vertical scroll indicator on the right edge of a [LazyColumn][androidx.compose.foundation.lazy.LazyColumn],
 * reflecting the current scroll position via [listState].
 *
 * The indicator appears when the list is actively scrolling and fades out after
 * [fadeOutDelayMs] of inactivity. It uses `ink3` at 30% alpha to match the
 * Paper palette's quiet, low-contrast aesthetic.
 *
 * Design rationale: the indicator is 4dp wide with 2dp corner radius, inset 2dp
 * from the right edge. It has a minimum height of 32dp so it remains a visible
 * touch target even in very long lists. The colour is `onSurface`-equivalent at
 * low alpha — specifically `ink` (#1a1f28) at 0.3 — so it recedes against the
 * `bg` background without competing with article titles.
 */
@Composable
fun Modifier.lazyColumnScrollbar(
    listState: LazyListState,
    thumbColor: Color = Color(0x4D1A1F28), // ink (#1a1f28) at 0.30 alpha
    thumbWidthDp: Float = 4f,
    thumbCornerRadiusDp: Float = 2f,
    thumbMinHeightDp: Float = 32f,
    rightInsetDp: Float = 2f,
    fadeOutDelayMs: Long = 1500L,
): Modifier {
    // Track whether the user is actively scrolling
    val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }

    // The "visible" flag stays true while scrolling and for a delay after
    var indicatorVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isScrolling) {
        if (isScrolling) {
            indicatorVisible = true
        } else {
            delay(fadeOutDelayMs)
            indicatorVisible = false
        }
    }

    // Animate alpha for the fade-in / fade-out
    val alpha by animateFloatAsState(
        targetValue = if (indicatorVisible) 1f else 0f,
        animationSpec = tween(durationMillis = if (indicatorVisible) 150 else 500),
        label = "scrollbar-alpha",
    )

    val layoutInfo by remember { derivedStateOf { listState.layoutInfo } }

    return this
        .testTag(ScrollIndicatorTestTag)
        .drawWithContent {
            drawContent()

            val info = layoutInfo
            val totalItems = info.totalItemsCount
            if (totalItems == 0 || alpha == 0f) return@drawWithContent

            val visibleItems = info.visibleItemsInfo
            if (visibleItems.isEmpty()) return@drawWithContent

            // Compute the fraction of the list that is visible
            val firstVisible = visibleItems.first()
            val lastVisible = visibleItems.last()

            // Estimate total content height from the average visible item height
            val visibleItemCount = visibleItems.size
            val avgItemHeight = visibleItems.sumOf { it.size } / visibleItemCount.toFloat()
            val estimatedTotalHeight = avgItemHeight * totalItems

            // The viewport height (the visible area)
            val viewportHeight = info.viewportEndOffset - info.viewportStartOffset

            // If total content fits in the viewport, no scrollbar needed
            if (estimatedTotalHeight <= viewportHeight) return@drawWithContent

            // Calculate thumb size proportional to viewport / total content
            val thumbWidthPx = thumbWidthDp * density
            val thumbMinHeightPx = thumbMinHeightDp * density
            val rightInsetPx = rightInsetDp * density
            val cornerRadiusPx = thumbCornerRadiusDp * density

            val trackHeight = size.height
            val rawThumbHeight = (viewportHeight.toFloat() / estimatedTotalHeight) * trackHeight
            val thumbHeight = rawThumbHeight.coerceAtLeast(thumbMinHeightPx)

            // Calculate scroll position (0..1)
            val scrollOffset = firstVisible.index * avgItemHeight +
                (firstVisible.offset - info.viewportStartOffset).coerceAtLeast(0)
            val maxScrollOffset = (estimatedTotalHeight - viewportHeight).coerceAtLeast(1f)
            val scrollFraction = (scrollOffset / maxScrollOffset).coerceIn(0f, 1f)

            // Position the thumb
            val thumbTop = scrollFraction * (trackHeight - thumbHeight)

            drawRoundRect(
                color = thumbColor.copy(alpha = thumbColor.alpha * alpha),
                topLeft = Offset(
                    x = size.width - thumbWidthPx - rightInsetPx,
                    y = thumbTop,
                ),
                size = Size(thumbWidthPx, thumbHeight),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
            )
        }
}
