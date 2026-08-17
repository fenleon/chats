package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Scrollbar for the thread's reverseLayout LazyColumn. The SDK's
 * [com.thelightphone.sdk.ui.LightLazyScrollView] assumes uniform row heights,
 * which message rows don't have, and its [com.thelightphone.sdk.ui.LightScrollView]
 * bar is private; the Compose 2026 BOM (foundation 1.10) removed the stock
 * scrollbar API entirely. So this is a small port of the SDK's bar (identical
 * rail + thumb, drag + tap), fed by metrics estimated from the real lazy
 * layout instead of a uniform height.
 */

private const val SCROLLBAR_WIDTH_UNITS = 2f
private const val MIN_THUMB_FRACTION = 0.1f
private const val MAX_THUMB_FRACTION = 0.85f

/** Scroll metrics for a lazy list, estimated from the real layout info
 *  (viewport × itemCount / average visible height — the estimate the old
 *  foundation scrollbar used). Flipped for the thread's reverseLayout so the
 *  thumb sits at the bottom of the track when the list is at its end
 *  (the newest message). */
internal class ThreadListMetrics(
    val scrollPx: Float,
    val maxScrollPx: Float,
    val avgItemHeightPx: Float,
) {
    val overflows: Boolean get() = maxScrollPx > 0f
    /** Display offset for the reverse-layout track: 0 = newest (list end). */
    val displayScrollPx: Float get() = maxScrollPx - scrollPx
}

internal fun LazyListState.threadListMetrics(): ThreadListMetrics {
    val layoutInfo = layoutInfo
    // viewportStartOffset/EndOffset are the visible viewport's pixel bounds —
    // in reverseLayout the coordinate space is flipped (start can sit above
    // end), so take the absolute height; this also avoids this Compose
    // version's packed `Size` accessor.
    val viewportHeightPx = abs(layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    val visible = layoutInfo.visibleItemsInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0 || visible.isEmpty()) return ThreadListMetrics(0f, 0f, 0f)
    // LazyListItemInfo.size is the item's pixel height in this Compose version.
    var totalVisibleHeightPx = 0
    visible.forEach { totalVisibleHeightPx += it.size }
    val avgItemHeightPx = totalVisibleHeightPx / visible.size.toFloat()
    val totalContentPx = totalItems * avgItemHeightPx
    val maxScrollPx = (totalContentPx - viewportHeightPx).coerceAtLeast(0f)
    // reverseLayout: the scroll origin is the bottom (index 0 = newest), so the
    // scroll offset is the laid-out `offset` of the bottom-edge visible item
    // (the lowest index). Index×avg estimates go stale with variable row
    // heights; the real offset always tracks the scroll.
    val scrollPx = visible.minByOrNull { it.index }?.offset?.toFloat()
        ?.coerceAtMost(maxScrollPx) ?: 0f
    return ThreadListMetrics(scrollPx, maxScrollPx, avgItemHeightPx)
}

private data class ScrollBarGeometry(
    val trackWidthPx: Float,
    val trackHeightPx: Float,
    val touchWidthPx: Float,
    val contentScrollOffsetPx: Float,
    val maxContentScrollOffsetPx: Float,
) {
    private val contentHeightPx = trackHeightPx + maxContentScrollOffsetPx
    private val visibleContentFraction = trackHeightPx / contentHeightPx
    private val contentScrollFraction = (contentScrollOffsetPx / maxContentScrollOffsetPx).coerceIn(0f, 1f)
    private val touchLeftPx = (trackWidthPx - touchWidthPx) / 2f
    private val touchRightPx = touchLeftPx + touchWidthPx

    val thumbHeightPx = trackHeightPx * visibleContentFraction.coerceIn(MIN_THUMB_FRACTION, MAX_THUMB_FRACTION)
    val maxThumbOffsetPx = trackHeightPx - thumbHeightPx
    val thumbOffsetPx = contentScrollFraction * maxThumbOffsetPx

    fun containsTouchX(xPx: Float): Boolean =
        xPx in touchLeftPx..touchRightPx

    fun containsThumb(xPx: Float, yPx: Float): Boolean =
        containsTouchX(xPx) &&
            yPx >= thumbOffsetPx &&
            yPx <= thumbOffsetPx + thumbHeightPx

    fun contentScrollOffsetToPlaceThumbTopAt(thumbTopPx: Float): Float {
        val fraction = (thumbTopPx / maxThumbOffsetPx).coerceIn(0f, 1f)
        return fraction * maxContentScrollOffsetPx
    }
}

@Composable
internal fun ThreadScrollBar(
    contentScrollOffsetPx: Float,
    maxContentScrollOffsetPx: Float,
    onScrollTo: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barColor = LightThemeTokens.colors.content
    val density = LocalDensity.current
    val trackWidth = SCROLLBAR_WIDTH_UNITS.gridUnitsAsDp()
    val railWidth = 1.dp
    val thumbWidth = 5.dp
    val touchWidth = thumbWidth * 6

    BoxWithConstraints(
        modifier = modifier.width(trackWidth),
        contentAlignment = Alignment.TopCenter,
    ) {
        val trackHeightPx = with(density) { maxHeight.toPx() }
        if (trackHeightPx <= 0f) return@BoxWithConstraints

        val geometry = ScrollBarGeometry(
            trackWidthPx = with(density) { trackWidth.toPx() },
            trackHeightPx = trackHeightPx,
            touchWidthPx = with(density) { touchWidth.toPx() },
            contentScrollOffsetPx = contentScrollOffsetPx,
            maxContentScrollOffsetPx = maxContentScrollOffsetPx,
        )
        val thumbOffsetDp = with(density) { geometry.thumbOffsetPx.toDp() }
        val thumbHeightDp = with(density) { geometry.thumbHeightPx.toDp() }
        val currentOnScrollTo by rememberUpdatedState(onScrollTo)
        val currentGeometry by rememberUpdatedState(geometry)

        fun handleTrackTap(xPx: Float, yPx: Float) {
            val geometry = currentGeometry
            if (!geometry.containsTouchX(xPx)) return
            if (geometry.containsThumb(xPx, yPx)) return
            val targetThumbTopPx = yPx - geometry.thumbHeightPx / 2f
            currentOnScrollTo(geometry.contentScrollOffsetToPlaceThumbTopAt(targetThumbTopPx))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startGeometry = currentGeometry
                        if (!startGeometry.containsThumb(down.position.x, down.position.y)) {
                            return@awaitEachGesture
                        }

                        down.consume()
                        val dragStartThumbOffsetPx = startGeometry.thumbOffsetPx
                        var dragAmountPx = 0f

                        drag(down.id) { change ->
                            change.consume()
                            val geometry = currentGeometry

                            dragAmountPx += change.position.y - change.previousPosition.y
                            val newThumbTop = (dragStartThumbOffsetPx + dragAmountPx)
                                .coerceIn(0f, geometry.maxThumbOffsetPx)
                            currentOnScrollTo(geometry.contentScrollOffsetToPlaceThumbTopAt(newThumbTop))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        handleTrackTap(offset.x, offset.y)
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .background(barColor),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = thumbOffsetDp)
                    .width(trackWidth)
                    .height(thumbHeightDp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(thumbWidth)
                        .fillMaxHeight()
                        .background(barColor),
                )
            }
        }
    }
}
