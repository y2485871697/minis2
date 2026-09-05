package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * [T-android-stream-growth-glitch] reverseLayout pins the growing streaming
 * row's clipped edge, so its growth shifts the whole transcript up for the
 * frames between the measure and the dispatchRawDelta correction. The bridge
 * pre-pays that shift within the measure pass; these tests pin the accounting
 * rules it shares with the reading-anchor collector.
 */
class ReadingShiftBridgeTest {
    private fun bridge() = ReadingShiftBridge()

    private fun ReadingShiftBridge.observe(
        key: Any = "live",
        size: Int,
        live: Boolean = true,
        reading: Boolean = true,
        draining: Boolean = true,
    ) = onAnchorObserved(key, size, live, reading, draining)

    @Test fun growthWhileReadingIsPrePaidTheSameFrame() {
        val b = bridge()
        assertEquals(0, b.observe(size = 100))
        assertEquals(67, b.observe(size = 167))
        assertEquals(134, b.observe(size = 234))
    }

    @Test fun takePendingHandsTheDebtToTheRealScrollCorrection() {
        val b = bridge()
        b.observe(size = 100)
        b.observe(size = 167)
        assertEquals(67, b.takePending())
        assertEquals(0, b.takePending())
        // After the dispatch the next measure must not translate again.
        assertEquals(0, b.observe(size = 167))
    }

    @Test fun shrinkageIsNotCompensated() {
        val b = bridge()
        b.observe(size = 300)
        assertEquals(0, b.observe(size = 233))
        assertEquals(0, b.takePending())
    }

    @Test fun atBottomOrIdleStreamAccumulatesNothing() {
        val b = bridge()
        b.observe(size = 100)
        assertEquals(0, b.observe(size = 167, reading = false))
        assertEquals(0, b.observe(size = 234, draining = false))
        assertEquals(0, b.takePending())
    }

    @Test fun nonLiveAnchorGrowthIsIgnoredUntilTheRowWasSeenLive() {
        val b = bridge()
        assertEquals(0, b.observe(size = 100, live = false))
        // Never seen live: post-stream reflow of a frozen row counts nothing.
        assertEquals(0, b.observe(size = 180, live = false))
        // Latched live, then frozen mid-drain: the drain-window growth still
        // counts, matching the collector's liveAnchorKey latch.
        b.observe(size = 180, live = true)
        assertEquals(57, b.observe(size = 237, live = false))
    }

    @Test fun newAnchorKeyRestartsTheBaselineWithoutStaleDebt() {
        val b = bridge()
        b.observe(key = "block-1", size = 100)
        b.observe(key = "block-1", size = 167)
        // Markdown boundary completed: the live tail re-keys. The old debt
        // belonged to the old anchor and the collector reset alongside.
        assertEquals(0, b.observe(key = "block-2", size = 40))
        assertEquals(0, b.takePending())
    }

    @Test fun readingTurnedOffDropsTheDebt() {
        val b = bridge()
        b.observe(size = 100)
        b.observe(size = 167)
        assertEquals(0, b.observe(size = 234, reading = false))
        assertEquals(0, b.takePending())
    }

    private fun source(name: String): String = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .map { File(it, "com/openminis/app/ui/chat/$name.kt") }.first { it.isFile }
        .readText().replace("\r\n", "\n")

    @Test fun lazyColumnPrePaysShiftAndDrainsDebtPerFrame() {
        val screen = source("ChatScreen")
        assertTrue(screen.contains(".readingShiftBridge {"))
        assertTrue(screen.contains("streamShiftBridge.onAnchorObserved("))
        assertTrue(screen.contains("val owed = streamShiftBridge.takePending()"))
        assertTrue(screen.contains("withFrameNanos { }"))
        assertTrue("the collector must not defer all placement debt until stream end",
            !screen.contains("if (viewModel.isStreaming.value ||"))
        assertTrue("collector must drain the bridge, not double-apply its own diff",
            !screen.contains("dispatchRawDelta(growth.toFloat())"))
        assertTrue(screen.contains("Snapshot.withoutReadObservation"))
    }
}
