package com.openminis.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout

/**
 * The detached-reading freeze, rebuilt after the height-freeze scheme
 * (ReadingFreeze.kt, deleted) with its three measured failure modes
 * structurally eliminated. The freeze keeps the transcript pixel-stable
 * while the reader is away from the live edge: rows report the height they
 * had when the reader last settled, streaming growth accumulates in the
 * row's clipped overflow below the fold, and the tail reveals when the
 * reader scrolls back down. The measurement frame is the only zero-latency
 * compensation point — here the "compensation" is the height report itself,
 * written in-measure, so no scroll command runs while detached (the measured
 * 12:51 sawtooth: scroll corrections issued outside the measure frame always
 * presented the grown frame first and corrected one frame later).
 *
 * The rebuild, lesson by lesson:
 *
 *  1. Handoff staleness. The old freeze captured one anchor height per row
 *     at engage and reported it unconditionally, so the thinking→text
 *     handoff (a row collapsing from its reasoning body to a header while a
 *     text row appears below) left stale height and stale content glued
 *     together. Now each row reports min(real, level): a shrink shows
 *     instantly, only growth is hidden, and the level follows the shrink —
 *     there is no stale state to go stale.
 *  2. Zero-height recycle collapse. A zero-height measure (the recycled
 *     item re-entering the viewport) updates nothing: the level persists,
 *     the row re-measures at its real height, and the delta vs the level is
 *     treated as growth (hidden) instead of collapsing the row.
 *  3. The release jump. The withheld tail is accounted continuously
 *     ([hiddenPx]); when the freeze lifts away from the live edge the whole
 *     tail is handed to the viewport as one raw delta so the reading point
 *     does not jump (the old scheme's release rule, kept verbatim). At the
 *     live edge itself no compensation runs — the reveal is the stream the
 *     reader asked for.
 *
 * Motion handling, also measured on device: both scroll directions keep the
 * freeze, so travel runs over a static transcript at true 1:1. Releasing the
 * levels on every downward frame exposed each concurrent 71-168px streaming
 * growth step and moved the reading point by exactly that amount. The tail now
 * reveals only after the reader settles at the live edge. Gesture frames defer
 * shrink-following (the async markdown renderer's own per-remeasure height
 * jitter, +/-10-60px on a row whose laid-out size steps monotonically, is
 * filtered by the collapse threshold); still frames follow every shrink,
 * where the measurements are clean.
 */
internal class ReadingAnchorState {
    /** Frozen reported height per row key (the freeze level). */
    internal val frozenHeights = HashMap<Any, Int>()

    /** Per-row hidden growth (real - reported), refreshed every pass. */
    internal val hiddenPx = HashMap<Any, Int>()

    /** Sum of [hiddenPx] after the latest pass — the withheld tail. */
    internal var hiddenTotal: Int = 0

    /** Whether the freeze currently applies (detached + turn live). */
    internal var freezeActive: Boolean = false

    /** Whether the latest frame was a gesture frame. */
    internal var gestureFrames: Boolean = false

    /** One-shot viewport compensation for the tail when the freeze lifts. */
    internal var releaseCompensation by mutableStateOf(0)

    fun reset() {
        frozenHeights.clear()
        hiddenPx.clear()
        hiddenTotal = 0
    }
}

/**
 * Per-row freeze. Attach to EVERY chat row: while the freeze applies, the
 * row reports the height it had when its level was set (growth hidden in the
 * clipped overflow below the fold), follows real shrinks instantly (still
 * frames) or after a collapse-sized drop (gesture frames, filtering the
 * renderer's measurement jitter), and reports its real height otherwise.
 */
internal fun Modifier.liveRowFreeze(state: ReadingAnchorState, key: Any): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val real = placeable.height
        var reported = real
        if (state.freezeActive && real > 0) {
            val level = state.frozenHeights[key]
            when {
                level == null -> state.frozenHeights[key] = real
                state.gestureFrames && real < level - GESTURE_COLLAPSE_FOLLOW_PX ->
                    state.frozenHeights[key] = real
                !state.gestureFrames && real < level -> state.frozenHeights[key] = real
            }
            reported = state.frozenHeights[key] ?: real
            state.hiddenPx[key] = real - reported
        } else {
            state.hiddenPx.remove(key)
        }
        layout(placeable.width, reported) { placeable.placeRelative(0, 0) }
    }

/**
 * Attach to the chat LazyColumn, above the rows. Drives the freeze lifecycle:
 * engages while [active] (reader detached, turn live, adb opt-in), releases
 * at the live edge, and queues the withheld tail as
 * [ReadingAnchorState.releaseCompensation] when the freeze lifts away from
 * the edge.
 */
internal fun Modifier.readingFreezeHost(
    state: ReadingAnchorState,
    active: () -> Boolean,
    gestureActive: () -> Boolean,
    atLiveEdge: () -> Boolean,
    onLiveEdgeSettle: () -> Unit,
): Modifier = layout { measurable, constraints ->
    val nowActive = active()
    val wasActive = state.freezeActive
    state.freezeActive = nowActive
    state.gestureFrames = gestureActive()
    if (wasActive && !nowActive && state.hiddenTotal > 0 && !atLiveEdge()) {
        // The freeze lifts away from the live edge (the turn ended, the
        // reader re-followed): hand the withheld tail to the viewport as one
        // raw delta so the reading point does not jump when the rows report
        // their real heights.
        state.releaseCompensation = state.hiddenTotal
    }
    val placeable = measurable.measure(constraints)
    var hidden = 0
    for (value in state.hiddenPx.values) hidden += value
    state.hiddenTotal = hidden
    if (nowActive && atLiveEdge() && !gestureActive()) {
        // Settled at the live edge: release. Mid-gesture the arm logic owns
        // the intent — releasing per-frame would fight the drag that just
        // armed it.
        onLiveEdgeSettle()
        state.frozenHeights.clear()
        state.hiddenPx.clear()
        state.hiddenTotal = 0
    }
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/** A shrink smaller than this during a gesture frame is renderer jitter. */
private const val GESTURE_COLLAPSE_FOLLOW_PX = 96
