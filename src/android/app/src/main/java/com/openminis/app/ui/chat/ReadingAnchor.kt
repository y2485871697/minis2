package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
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
 * Growth arrives in two motion states, and each has its own zero-lag
 * channel:
 *
 *  - STILL frames (the reader paused to read): the decision runs inside the
 *    LazyColumn's own measure pass, after the rows have measured. glue = the
 *    topmost visible row, tracked BY KEY so index shifts from live-edge
 *    insertions and handoffs never break the reference; if the glue top edge
 *    rose by `rise` px under a still scroll position, requestScrollToItem
 *    writes the corrected position and a follow-up measure pass of the SAME
 *    frame applies it. The position STATE leads the measured GEOMETRY by a
 *    pass (the on-device 19:46 trace: trusting the state re-requested
 *    against stale geometry and ping-ponged ±71px), so an in-flight request
 *    is judged only by the glue row's measured offset.
 *  - MOVING frames (drag / fling): a nested-scroll connection absorbs the
 *    growth in the input-dispatch phase — OUTSIDE any measure pass, where
 *    dispatchRawDelta's inline remeasure is legal (from inside the list's
 *    own measure it trips "performMeasureAndLayout called during measure
 *    layout", the measured 19:13 crash). The connection subtracts the
 *    finger's consumed delta from the measured top-edge shift, so travel
 *    stays 1:1 over the content frame the reader left instead of wading
 *    through the growth that landed mid-swipe.
 *
 * requestScrollToItem — NOT dispatchRawDelta — applies the still-frame
 * correction: dispatchRawDelta force-remeasures synchronously (see above),
 * while requestScrollToItem only writes the scroll position and invalidates
 * the measurement. Because it cancels any in-progress scroll ("Any scroll in
 * progress will be cancelled"), the still-frame pass skips compensation
 * while the gesture flags are set — that is what the connection is for.
 * Positive correction = viewport toward older content = exactly the
 * direction that undoes a top-edge rise (measured 04:47 anchor trace:
 * growth=N → +N, snap back).
 *
 * At the live edge the anchor stands down entirely and releases the hard
 * pause: the reader who scrolled back to the bottom wants the stream, and
 * reverseLayout's native anchor plus the BottomFollowLayout pin take over
 * (the freeze's release-at-live-edge rule).
 */
internal class ReadingAnchorState {
    /** Key of the row the reading position glues to. */
    internal var glueKey: Any? = null

    /** Glue row's top-edge height above the viewport bottom, in px. */
    internal var glueTopPx: Int = 0

    internal var lastIdx: Int = -1
    internal var lastOff: Int = -1

    /** Delta requested in a previous pass and not yet seen in the geometry. */
    internal var pendingDelta: Int = 0

    /** The glue top edge the pending request was computed from. */
    internal var pendingTopPx: Int = 0

    internal var primed: Boolean = false

    /** Geometry published by the latest measure pass, for the gesture path. */
    internal var lastTopPx: Int = 0
    internal var lastTopKey: Any? = null

    /** Finger delta consumed since the last measure pass (scroll units). */
    internal var gestureConsumed: Int = 0

    fun reset() {
        glueKey = null
        glueTopPx = 0
        lastIdx = -1
        lastOff = -1
        pendingDelta = 0
        pendingTopPx = 0
        primed = false
        lastTopPx = 0
        lastTopKey = null
        gestureConsumed = 0
    }
}

/**
 * Attach to the chat LazyColumn. While [active] holds (reader detached, turn
 * live, adb opt-in), content-chain changes under a still viewport are
 * absorbed so the reading position never visibly moves; during drags and
 * flings the [ReadingAnchorConnection] absorbs them instead. See
 * [ReadingAnchorState] for the design and its measured history.
 */
internal fun Modifier.liveReadingAnchor(
    state: ReadingAnchorState,
    listState: LazyListState,
    active: () -> Boolean,
    gestureActive: () -> Boolean,
    atLiveEdge: () -> Boolean,
    onLiveEdgeSettle: () -> Unit,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    if (active()) {
        val idx = listState.firstVisibleItemIndex
        val off = listState.firstVisibleItemScrollOffset
        if (atLiveEdge()) {
            // The reader is back at the live edge: release the hard pause and
            // hand the viewport to the follow machinery. Nothing is held
            // here — compensating would pin the newest text below the fold.
            onLiveEdgeSettle()
            state.reset()
        } else {
            // Read the layout info written by the measure above UNOBSERVED:
            // an observed layoutInfo read inside a measure lambda
            // re-invalidates on the list's every-measure layoutInfo write and
            // ping-pongs passes.
            val info = Snapshot.withoutReadObservation { listState.layoutInfo }
            val visible = info.visibleItemsInfo
            // visibleItemsInfo can transiently be empty mid-measure
            // (T-android-scroll-isnearbottom-bug); keep the baseline, act
            // next pass.
            if (visible.isNotEmpty()) {
                // Last in layout order = topmost visible row = the oldest
                // content on screen; everything below it down to the live
                // edge glues to its top edge.
                val glue = visible.last()
                val top = glue.offset + glue.size
                state.lastTopKey = glue.key
                if (glue.size <= 0) {
                    // Recycle/prefetch artifact: a zero-height measure of a
                    // re-entering item must never become the reference (this
                    // is what collapsed rows to nothing under the freeze).
                } else if (gestureActive()) {
                    // Publish the finger-excluded geometry for the connection;
                    // growth during drags/flings is absorbed there.
                    state.lastTopPx = top + state.gestureConsumed
                    state.gestureConsumed = 0
                } else {
                    state.lastTopPx = top
                    state.gestureConsumed = 0
                    if (!state.primed || glue.key != state.glueKey) {
                        state.glueKey = glue.key
                        state.glueTopPx = top
                        state.lastIdx = idx
                        state.lastOff = off
                        state.pendingDelta = 0
                        state.primed = true
                    } else if (state.pendingDelta != 0) {
                        // A request is in flight. The position STATE leads the
                        // measured GEOMETRY by a pass (the on-device 19:46
                        // trace: trusting the state re-requested against stale
                        // geometry and ping-ponged ±71px) — judge only by the
                        // glue row's offset.
                        val expectedOffset = -(state.lastOff + state.pendingDelta)
                        if (glue.offset == expectedOffset) {
                            // Applied. Judge the residual against the target
                            // held across the apply; growth composed into the
                            // same frame is absorbed instead of re-baselined.
                            state.lastOff = off
                            val rise = top - state.glueTopPx
                            state.pendingDelta = 0
                            if (rise != 0) {
                                requestRise(state, listState, rise, glue.key, idx, off, top)
                            }
                        } else if (top != state.pendingTopPx || idx != state.lastIdx) {
                            // The geometry moved while the request was in
                            // flight (further growth, an external mover): the
                            // request is stale — adopt the current geometry.
                            state.pendingDelta = 0
                            state.glueTopPx = top
                            state.lastIdx = idx
                            state.lastOff = off
                        }
                        // else: still in flight — wait. A second request
                        // against the lagging geometry is exactly what
                        // oscillated on device.
                    } else {
                        val positionMoved = idx != state.lastIdx || off != state.lastOff
                        state.lastIdx = idx
                        state.lastOff = off
                        if (positionMoved) {
                            // A drag, fling or programmatic jump owns the
                            // viewport — follow it; never fight it.
                            state.glueTopPx = top
                        } else {
                            val rise = top - state.glueTopPx
                            if (rise != 0) {
                                requestRise(state, listState, rise, glue.key, idx, off, top)
                            }
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

private fun requestRise(
    state: ReadingAnchorState,
    listState: LazyListState,
    rise: Int,
    glueKey: Any?,
    idx: Int,
    off: Int,
    top: Int,
) {
    if (listState.isScrollInProgress) {
        // requestScrollToItem cancels any scroll in progress — a drag or
        // fling owns the viewport. Adopt the moved geometry instead of
        // fighting it; the anchor re-baselines through the follow-up passes.
        state.pendingDelta = 0
        state.glueTopPx += rise
        return
    }
    // Zero-lag write: the position lands at a follow-up measure pass of
    // THIS frame. A boundary clamp (rise larger than the remaining scroll
    // range — e.g. a collapse that lands the reader at the live edge) shows
    // up as a geometry mismatch against pendingTopPx and re-baselines there.
    listState.requestScrollToItem(idx, off + rise)
    state.pendingDelta = rise
    state.pendingTopPx = top
    if (ScrollDebugFlags.traceMoves) {
        AppLogger.debug(
            "ScrollReadingAnchor",
            "rise=$rise requested idx=$idx off=${off + rise} key=$glueKey",
        )
    }
}

/**
 * Absorbs live-row growth during drags and flings. Nested-scroll callbacks
 * run in the input-dispatch phase — outside any measure pass — where
 * dispatchRawDelta's inline remeasure is legal; from inside the list's own
 * measure it is the 19:13 crash. The finger's consumed delta is subtracted
 * from the measured top-edge shift (the modifier publishes the geometry net
 * of it), so swipes stay 1:1 over the content frame the reader left.
 */
internal class ReadingAnchorConnection(
    private val state: ReadingAnchorState,
    private val listState: LazyListState,
    private val active: () -> Boolean,
) : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (!active() || state.lastTopKey == null) return Offset.Zero
        state.gestureConsumed += consumed.y.roundToInt()
        if (state.lastTopKey != state.glueKey || state.pendingDelta != 0) {
            // Traveling across rows, or a measure-frame request is still in
            // flight: re-anchor without dispatching — a cross-row difference
            // is not growth.
            state.glueTopPx = state.lastTopPx
            return Offset.Zero
        }
        val growth = state.lastTopPx - state.glueTopPx
        if (growth != 0) {
            listState.dispatchRawDelta(growth.toFloat())
            state.glueTopPx = state.lastTopPx
            if (ScrollDebugFlags.traceMoves) {
                AppLogger.debug(
                    "ScrollReadingAnchor",
                    "gesture absorb growth=$growth key=${state.glueKey}",
                )
            }
        }
        return Offset.Zero
    }
}
