package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import com.openminis.app.logging.AppLogger

/**
 * Detached-reading anchor. Rows keep reporting their real height; growth is
 * absorbed by the viewport so newly streamed text remains visible and the
 * reader's existing content does not drift.
 *
 * Growth is measured with a monotonic per-row probe. Small upward/downward
 * renderer jitter is ignored; real chunk-sized increases are consumed in the
 * measure frame (still) or nested-scroll dispatch (gesture).
 */
internal class ReadingAnchorState {
    internal val rowHeights = HashMap<Any, Int>()
    internal val rowDeltas = HashMap<Any, Int>()
    internal var pendingGrowth: Int = 0

    internal var glueKey: Any? = null
    internal var glueTopPx: Int = 0
    internal var lastIdx: Int = -1
    internal var lastOff: Int = -1
    internal var pendingDelta: Int = 0
    internal var pendingTopPx: Int = 0
    internal var suppressAfterGesture: Boolean = false
    internal var primed: Boolean = false
    internal var lastTopPx: Int = 0
    internal var lastTopKey: Any? = null

    fun reset() {
        rowHeights.clear()
        rowDeltas.clear()
        pendingGrowth = 0
        glueKey = null
        glueTopPx = 0
        lastIdx = -1
        lastOff = -1
        pendingDelta = 0
        pendingTopPx = 0
        suppressAfterGesture = false
        primed = false
        lastTopPx = 0
        lastTopKey = null
    }
}

/** Per-row probe. Real height is always reported so streamed text stays visible. */
internal fun Modifier.growthProbe(state: ReadingAnchorState, key: Any): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = placeable.height
        if (height > 0) {
            val previous = state.rowHeights[key]
            when {
                previous == null -> state.rowHeights[key] = height
                height < previous - COLLAPSE_RESET_PX -> {
                    state.rowHeights[key] = height
                    state.rowDeltas.remove(key)
                }
                height > previous -> {
                    val delta = height - previous
                    state.rowHeights[key] = height
                    if (delta >= MIN_GROWTH_STEP_PX) {
                        state.rowDeltas[key] = (state.rowDeltas[key] ?: 0) + delta
                    }
                }
            }
        }
        layout(placeable.width, height) { placeable.placeRelative(0, 0) }
    }

/** Attach to the chat LazyColumn and compensate visible growth. */
internal fun Modifier.liveReadingAnchor(
    state: ReadingAnchorState,
    listState: LazyListState,
    active: () -> Boolean,
    gestureActive: () -> Boolean,
    atLiveEdge: () -> Boolean,
    onLiveEdgeSettle: () -> Unit,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    if (!active()) {
        state.reset()
    } else if (atLiveEdge()) {
        onLiveEdgeSettle()
        state.reset()
    } else {
        val info = Snapshot.withoutReadObservation { listState.layoutInfo }
        val visible = info.visibleItemsInfo
        if (visible.isNotEmpty()) {
            var growth = 0
            for (item in visible) growth += state.rowDeltas[item.key] ?: 0
            state.pendingGrowth = growth
            val glue = visible.last()
            val top = glue.offset + glue.size
            state.lastTopKey = glue.key
            if (glue.size > 0) {
                if (gestureActive()) {
                    state.lastTopPx = top
                } else {
                    state.pendingGrowth = 0
                    state.rowDeltas.clear()
                    val idx = listState.firstVisibleItemIndex
                    val off = listState.firstVisibleItemScrollOffset
                    if (!state.primed || glue.key != state.glueKey) {
                        state.glueKey = glue.key
                        state.glueTopPx = top
                        state.lastIdx = idx
                        state.lastOff = off
                        state.pendingDelta = 0
                        state.suppressAfterGesture = false
                        state.primed = true
                    } else if (state.suppressAfterGesture) {
                        // User scrolling owns the offset. Discard growth observed
                        // during the drag and rebase after it settles so the anchor
                        // never fights the finger with a second delta.
                        state.suppressAfterGesture = false
                        state.pendingDelta = 0
                        state.glueTopPx = top
                        state.lastIdx = idx
                        state.lastOff = off
                    } else if (state.pendingDelta != 0) {
                        val expectedOffset = -(state.lastOff + state.pendingDelta)
                        if (glue.offset == expectedOffset) {
                            state.lastOff = off
                            state.pendingDelta = 0
                            // The requested offset has been applied. Rebase to the
                            // measured frame; issuing a residual correction here can
                            // immediately reverse the same request and flicker.
                            state.glueTopPx = top
                        } else if (top != state.pendingTopPx) {
                            state.pendingDelta = 0
                            state.glueTopPx = top
                            state.lastIdx = idx
                            state.lastOff = off
                        }
                    } else {
                        val moved = idx != state.lastIdx || off != state.lastOff
                        state.lastIdx = idx
                        state.lastOff = off
                        if (moved) state.glueTopPx = top else {
                            val rise = top - state.glueTopPx
                            if (rise > 0) {
                                requestRise(state, listState, rise, glue.key)
                            } else if (rise < 0) {
                                // Shrink/jitter is not stream growth. Rebase without
                                // moving the reader to avoid an oscillating correction.
                                state.glueTopPx = top
                            }
                        }
                    }
                }
            }
        }
    }
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

private fun requestRise(state: ReadingAnchorState, listState: LazyListState, rise: Int, key: Any?) {
    if (rise <= 0) return
    if (listState.isScrollInProgress) {
        state.glueTopPx += rise
        return
    }
    val idx = listState.firstVisibleItemIndex
    val off = listState.firstVisibleItemScrollOffset
    listState.requestScrollToItem(idx, off + rise)
    state.pendingDelta = rise
    state.pendingTopPx = state.glueTopPx
    if (ScrollDebugFlags.traceMoves) {
        AppLogger.debug("ScrollReadingAnchor", "rise=$rise requested idx=$idx off=${off + rise} key=$key")
    }
}

internal class ReadingAnchorConnection(
    private val state: ReadingAnchorState,
    private val listState: LazyListState,
    private val active: () -> Boolean,
) : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!active()) return Offset.Zero
        if (source == NestedScrollSource.UserInput) {
            // Do not dispatch a second raw delta while the user is dragging. The
            // streamed row may grow underneath the finger; it is rebased once the
            // gesture settles instead of producing visible jitter.
            state.pendingGrowth = 0
            state.rowDeltas.clear()
            state.pendingDelta = 0
            state.suppressAfterGesture = true
            return Offset.Zero
        }
        val growth = state.pendingGrowth
        if (growth != 0) {
            state.pendingGrowth = 0
            state.rowDeltas.clear()
            listState.dispatchRawDelta(growth.toFloat())
            if (ScrollDebugFlags.traceMoves) AppLogger.debug("ScrollReadingAnchor", "gesture absorb growth=$growth")
        }
        return Offset.Zero
    }
}

private const val MIN_GROWTH_STEP_PX = 64
private const val COLLAPSE_RESET_PX = 96
