package com.openminis.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout

/**
 * Shared state for the detached-reading measurement freeze.
 *
 * A scroll-based reading anchor can only react one frame after a streaming
 * row's growth has been measured — the grown frame presents first, the
 * correction lands in the next one, and the reader sees the content bounce
 * (the measured 12:51 sawtooth: up ~71px, snap back, once per chunk). The
 * only zero-lag place to absorb the growth is the row's own measure pass.
 *
 * While [frozen], every live row reports the height captured at the freeze
 * point and clips its continuing growth below the fold: the transcript is
 * pixel-stable with zero scroll commands, and the tail accumulates
 * invisibly. [accumulatedPx] tracks how much growth is being withheld so the
 * releaser can hand it to the viewport as one raw delta when the reader is
 * away from the live edge.
 */
internal class ReadingFreezeState {
    var frozen by mutableStateOf(false)
    var accumulatedPx by mutableIntStateOf(0)

    /** Bumped on every freeze start so stale anchors never survive a release. */
    var freezeEpoch by mutableIntStateOf(0)
}

/** Per-row measurement bookkeeping; one instance per live row via remember(key). */
internal class RowFreezeCtl {
    var anchorPx = -1
    var anchorEpoch = -1
}

/**
 * Freeze THIS row's reported height while [ReadingFreezeState.frozen] is on.
 * Top-aligned placement keeps the content's top edge — and therefore every
 * reading position inside the row — fixed; the appended tail overflows the
 * reported bounds and is clipped by [clipToBounds].
 */
internal fun Modifier.liveRowReadingFreeze(
    ctl: RowFreezeCtl,
    state: ReadingFreezeState,
    active: Boolean,
): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    if (active && state.frozen) {
        if (ctl.anchorEpoch != state.freezeEpoch) {
            ctl.anchorEpoch = state.freezeEpoch
            ctl.anchorPx = placeable.height
        }
        state.accumulatedPx = placeable.height - ctl.anchorPx
        layout(placeable.width, ctl.anchorPx) { placeable.placeRelative(0, 0) }
    } else {
        ctl.anchorEpoch = -1
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}
