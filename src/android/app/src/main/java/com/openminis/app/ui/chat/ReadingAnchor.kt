package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import com.openminis.app.logging.AppLogger
import kotlin.math.roundToInt

/**
 * Measure-frame reading anchor for detached reading during a live turn.
 *
 * Supersedes the height-freeze scheme (ReadingFreeze.kt, deleted). The freeze
 * held every live row's reported height at the freeze point and clipped the
 * appended tail, which kept the transcript pixel-stable but carried three
 * measured failure modes, all rooted in lying about the row height instead of
 * moving the viewport:
 *
 *  1. The frozen anchor went stale across the thinking→text row handoff — the
 *     row's content shape changes (reasoning body collapses into a header, a
 *     text row appears below it), so the captured height stopped describing
 *     the row and the engage/epoch/thaw bookkeeping fought the swap.
 *  2. A zero-height measure — the recycled/prefetched item re-entering the
 *     viewport measured empty — captured as the anchor collapsed the whole
 *     streaming row to nothing (the vanished-message repro).
 *  3. The withheld tail had to be handed to the viewport as one raw delta on
 *     release, an unavoidable jump, with hysteresis (frozenOff/thawOff)
 *     wrapped around it.
 *
 * The rikkahub ChatList.kt model this replaces it with: never hide growth,
 * never fight the finger. At the live edge, reverseLayout's native bottom
 * anchor plus the BottomFollowLayout pin already hold the newest row. When
 * the reader is DETACHED, the growing tail sits below the reading position
 * under reverseLayout, and this anchor absorbs its growth with zero visual
 * lag — the measure frame is the only zero-latency compensation point (the
 * measured 12:51 sawtooth: corrections issued from a snapshotFlow collector,
 * via dispatchRawDelta or requestScrollToItem alike, presented the grown
 * frame first and corrected one frame later; a synchronous dispatchRawDelta
 * from the collector re-entered measure visibly instead).
 *
 * So the decision runs inside the LazyColumn's own measure pass, after the
 * rows have measured, per pass:
 *
 *  - glue = the topmost visible row, tracked BY KEY, so index shifts from
 *    live-edge insertions and handoffs never break the reference. Everything
 *    on screen between the glue row and the live edge is rigidly glued to
 *    its top edge (`offset + size` = that edge's height above the viewport
 *    bottom — the measured reverseLayout convention, anchor.offset ==
 *    -firstOffset).
 *  - if the scroll position moved since the last pass — drag, fling, or a
 *    programmatic jump — the mover owns the viewport: re-baseline, never
 *    fight it.
 *  - else if the glue top edge rose by `rise` px — streaming growth, a
 *    handoff collapse, an insertion below, anything that changed the content
 *    chain under a still viewport — dispatch exactly `rise` px through
 *    dispatchRawDelta. The position write re-measures the list within this
 *    same frame, so the reader never sees the intermediate geometry.
 *  - zero-height measures (recycle/prefetch artifacts) and key changes are
 *    re-baselined, never read as growth.
 *
 * dispatchRawDelta rather than requestScrollToItem: it neither enters the
 * scroll mutator mutex nor cancels an in-progress gesture ("Any scroll in
 * progress will be cancelled" — requestScrollToItem's contract), so deltas
 * compose with a held finger, and it keeps LazyColumn's key-based position
 * maintenance intact. Positive delta = viewport toward older content =
 * exactly the direction that undoes a top-edge rise (measured 04:47 anchor
 * trace: growth=N → +N consumed, snap back).
 */
internal class ReadingAnchorState {
    /** Key of the row the reading position glues to. */
    internal var glueKey: Any? = null

    /** Glue row's top-edge height above the viewport bottom, in px. */
    internal var glueTopPx: Int = 0

    internal var lastIdx: Int = -1
    internal var lastOff: Int = -1

    /** Delta dispatched in the previous pass and not yet seen applied. */
    internal var pendingDelta: Int = 0

    internal var primed: Boolean = false

    fun reset() {
        glueKey = null
        glueTopPx = 0
        lastIdx = -1
        lastOff = -1
        pendingDelta = 0
        primed = false
    }
}

/**
 * Attach to the chat LazyColumn. While [active] holds (reader detached, turn
 * live, adb opt-in), every measure pass absorbs content-chain changes under
 * a still viewport so the reading position never visibly moves. See
 * [ReadingAnchorState] for the design and its measured history.
 */
internal fun Modifier.liveReadingAnchor(
    state: ReadingAnchorState,
    listState: LazyListState,
    active: () -> Boolean,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    if (active()) {
        val idx = listState.firstVisibleItemIndex
        val off = listState.firstVisibleItemScrollOffset
        // Read the layout info written by the measure above UNOBSERVED: an
        // observed layoutInfo read inside a measure lambda re-invalidates on
        // the list's every-measure layoutInfo write and ping-pongs passes.
        val info = Snapshot.withoutReadObservation { listState.layoutInfo }
        val visible = info.visibleItemsInfo
        // visibleItemsInfo can transiently be empty mid-measure
        // (T-android-scroll-isnearbottom-bug); keep the baseline, act next pass.
        if (visible.isNotEmpty()) {
            // Last in layout order = topmost visible row = the oldest content
            // on screen; everything below it down to the live edge glues to
            // its top edge.
            val glue = visible.last()
            val top = glue.offset + glue.size
            if (glue.size <= 0) {
                // Recycle/prefetch artifact: a zero-height measure of a
                // re-entering item must never become the reference (this is
                // what collapsed rows to nothing under the freeze scheme).
            } else if (!state.primed || glue.key != state.glueKey) {
                state.glueKey = glue.key
                state.glueTopPx = top
                state.lastIdx = idx
                state.lastOff = off
                state.pendingDelta = 0
                state.primed = true
            } else {
                val ourDeltaApplied = state.pendingDelta != 0 &&
                    idx == state.lastIdx && off == state.lastOff + state.pendingDelta
                if (ourDeltaApplied) {
                    // Our compensation landed. Judge the residual against the
                    // target held across the apply, so growth composed into
                    // the same frame is absorbed too instead of re-baselined.
                    state.lastIdx = idx
                    state.lastOff = off
                    val rise = top - state.glueTopPx
                    if (rise != 0) {
                        dispatchRise(state, listState, rise, glue.key, idx, off)
                    } else {
                        state.pendingDelta = 0
                    }
                } else {
                    val positionMoved = idx != state.lastIdx || off != state.lastOff
                    state.lastIdx = idx
                    state.lastOff = off
                    if (positionMoved) {
                        // A drag, fling or programmatic jump owns the viewport
                        // — follow it; never fight it.
                        state.pendingDelta = 0
                        state.glueTopPx = top
                    } else {
                        val rise = top - state.glueTopPx
                        if (rise != 0) {
                            dispatchRise(state, listState, rise, glue.key, idx, off)
                        }
                    }
                }
            }
        }
    } else {
        state.reset()
    }
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

private fun dispatchRise(
    state: ReadingAnchorState,
    listState: LazyListState,
    rise: Int,
    glueKey: Any?,
    idx: Int,
    off: Int,
) {
    val consumed = listState.dispatchRawDelta(rise.toFloat()).roundToInt()
    if (consumed == 0) {
        // Clamped at a list boundary (e.g. a collapse larger than the
        // reading depth lands at the live edge): adopt the applied geometry.
        state.glueTopPx = state.glueTopPx + rise
        state.pendingDelta = 0
    } else {
        // Target stays; the follow-up pass re-judges at the applied position.
        state.pendingDelta = consumed
    }
    if (ScrollDebugFlags.traceMoves) {
        AppLogger.debug(
            "ScrollReadingAnchor",
            "rise=$rise consumed=$consumed key=$glueKey firstIdx=$idx firstOff=$off",
        )
    }
}
