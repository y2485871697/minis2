package com.openminis.app.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/**
 * [T-android-stream-growth-glitch] Same-frame visual pre-payment for the
 * reading-anchor scroll compensation.
 *
 * While the user reads a message that is still streaming, the growing live
 * row is usually the bottom-most visible LazyColumn item. reverseLayout pins
 * that row's clipped edge, so every height growth shifts ALL content above it
 * up by the growth amount in the very frame the growth is measured. The
 * reading-anchor collector corrects this with `dispatchRawDelta`, but a
 * collector — and any correction applied through it — can only observe a
 * committed layout, i.e. it lands at least one frame after the shift. That
 * single-frame jump, repeated on every streamed chunk, is the "streaming
 * message keeps flickering" report (frame-stepping the 2026-09-06 recording
 * shows the whole transcript bouncing by one line at the chunk cadence).
 *
 * The bridge closes the gap inside the measure pass itself:
 *  - a `Modifier.layout` wrapping the LazyColumn calls [onAnchorObserved]
 *    AFTER the inner measure, when `layoutInfo` already reflects this
 *    frame's row sizes. Growth of the anchored live row is accumulated into
 *    [pending] and returned, and the modifier places the list with
 *    `placeRelative(0, pending)` — cancelling the shift in the SAME frame
 *    it would have been visible.
 *  - while the response is still streaming, the collector does not also move
 *    the LazyColumn. The pending translation stays active, so there is no
 *    measure/scroll hand-off on every chunk. Once streaming and its final
 *    layout drain finish, the collector takes the accumulated amount once
 *    and transfers it to the real scroll position.
 *
 * The accounting deliberately mirrors the collector's rules (live-row latch,
 * drain window, reading gate) so the pre-paid amount and the dispatched
 * amount agree; `takePending` is the single authority for what is still owed,
 * which keeps the two from double-applying a correction.
 */
internal class ReadingShiftBridge {
    private var key: Any? = null
    private var size = 0
    private var liveLatch = false
    private var pending = 0

    fun reset() {
        key = null
        size = 0
        liveLatch = false
        pending = 0
    }

    /** Claim the still-uncompensated shift; called right before dispatching it as a real scroll delta. */
    fun takePending(): Int {
        val owed = pending
        pending = 0
        return owed
    }

    /**
     * Feed one measure pass's observation of the bottom-most visible row.
     * Returns the pixel offset the caller should translate the list by for
     * this frame (0 once the scroll correction has caught up).
     */
    fun onAnchorObserved(
        key: Any?,
        size: Int,
        live: Boolean,
        reading: Boolean,
        draining: Boolean,
    ): Int {
        if (!reading || key == null) {
            reset()
            return 0
        }
        // Keep the visual debt until the collector can transfer it once at
        // stream end. Resetting here would remove the placement compensation
        // before the real scroll correction runs.
        if (!draining) return 0
        if (key != this.key) {
            this.key = key
            this.size = size
            liveLatch = live
            pending = 0
            return 0
        }
        if (live) liveLatch = true
        val growth = size - this.size
        this.size = size
        if (growth > 0 && liveLatch) pending += growth
        return pending
    }
}

/**
 * Wrap the chat LazyColumn so growth of the anchored streaming row is
 * visually cancelled in the frame it is measured (see [ReadingShiftBridge]).
 * [syncFrame] runs between the inner measure and placement, so it may read
 * the freshly updated `listState.layoutInfo`; it must be read without
 * registering a snapshot observer — a measure-phase read of a state that the
 * inner measure itself writes would re-invalidate this node every tick.
 */
internal fun Modifier.readingShiftBridge(syncFrame: () -> Int): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val shift = syncFrame()
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, shift)
            }
        },
    )
