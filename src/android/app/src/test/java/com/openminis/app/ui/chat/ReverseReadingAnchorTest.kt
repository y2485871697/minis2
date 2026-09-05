package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReverseReadingAnchorTest {
    private fun anchor(size: Int = 1500, offset: Int = 200) =
        ReverseReadingAnchor("live-text", size, offset, 400, 800)
    @Test fun growthInsideTheSameMessageKeepsTheSameTextAtTheTop() {
        val before = anchor()
        val after = anchor(size = 1600)
        val correctedOffset = after.scrollOffset + after.growthSince(before)
        assertEquals(before.viewportHeight + before.scrollOffset - before.size,
            after.viewportHeight + correctedOffset - after.size)
    }
    @Test fun correctionDoesNotUndoSimultaneousFingerMovement() {
        val before = anchor()
        val after = anchor(size = 1560, offset = 240)
        val correctedOffset = after.scrollOffset + after.growthSince(before)
        assertEquals(40, (after.viewportHeight + correctedOffset - after.size) -
            (before.viewportHeight + before.scrollOffset - before.size))
    }
    @Test fun repeatedMeasurementsCannotCreateAScrollLoop() {
        val before = anchor()
        val after = anchor(size = 1560)
        assertEquals(60, after.growthSince(before))
        assertEquals(0, after.copy(scrollOffset = 260).growthSince(after))
    }
    @Test fun leavingTheGeneratingRowResetsTheAnchor() {
        assertEquals(0, anchor(size = 1800).copy(key = "history").growthSince(anchor()))
    }
    @Test fun plainDragAndFlingNeedNoCorrection() {
        assertEquals(0, anchor(offset = 500).growthSince(anchor()))
    }
    @Test fun rotationKeyboardAndPaddingChangesResetGeometry() {
        val previous = anchor()
        assertEquals(0, anchor(1800).copy(viewportWidth = 500).growthSince(previous))
        assertEquals(0, anchor(1800).copy(viewportHeight = 500).growthSince(previous))
        assertEquals(0, anchor(1800).copy(beforePadding = 30).growthSince(previous))
        assertEquals(0, anchor(1800).copy(afterPadding = 30).growthSince(previous))
    }
    @Test fun collapseAndMissingInitialMeasurementDoNotPushTheUserDown() {
        assertEquals(0, anchor(1200).growthSince(anchor()))
        assertEquals(0, anchor().growthSince(null))
    }
    @Test fun cumulativeStreamingGrowthPreservesReadingPosition() {
        var current = anchor()
        val top = current.viewportHeight + current.scrollOffset - current.size
        repeat(50) {
            val next = current.copy(size = current.size + 17)
            current = next.copy(scrollOffset = next.scrollOffset + next.growthSince(current))
            assertEquals(top, current.viewportHeight + current.scrollOffset - current.size)
        }
    }
    @Test fun integrationIsLimitedToPausedLiveRowsAndDoesNotCancelUserScroll() {
        val sources = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        val source = sources.map { File(it, "com/openminis/app/ui/chat/ChatScreen.kt") }
            .first { it.isFile }.readText()
        val bridge = sources.map { File(it, "com/openminis/app/ui/chat/ReadingShiftBridge.kt") }
            .first { it.isFile }.readText()
        assertTrue(source.contains("userScrolledAway && pendingSearchMessageId == null"))
        // Growth accounting lives in the measure-phase bridge; the collector
        // only drains what the bridge pre-paid, never re-diffs on its own.
        assertTrue(source.contains(".readingShiftBridge {"))
        assertTrue(source.contains("val owed = streamShiftBridge.takePending()"))
        assertTrue(source.contains("listState.dispatchRawDelta(owed.toFloat())"))
        assertTrue(bridge.contains("growth > 0 && liveLatch"))
        assertTrue(bridge.contains("!reading || key == null"))
        assertTrue(source.contains("verticalArrangement = Arrangement.spacedBy(2.dp),"))
    }
}
