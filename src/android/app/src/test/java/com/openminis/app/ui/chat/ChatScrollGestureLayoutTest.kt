package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ChatScrollGestureLayoutTest {
    private fun layout(index: Int = 0, offset: Int = 0, total: Int = 10,
        scrolling: Boolean = false, dragging: Boolean = false, pending: Boolean = true) =
        ChatScrollGestureLayout(index, offset, total, scrolling, dragging, pending)

    @Test fun onePixelAwayMustKeepFollowPaused() {
        assertTrue(layout(offset = 1).shouldSettle)
        assertFalse(layout(offset = 1).atBottom)
    }
    @Test fun oldNearBottomThresholdCannotResumeFollowing() {
        for (offset in listOf(2, 15, 32, 96)) assertFalse(layout(offset = offset).atBottom)
    }
    @Test fun previousRowIsNotTheBottomEvenAtZeroOffset() {
        assertFalse(layout(index = 1).atBottom)
        assertFalse(layout(index = 50).atBottom)
    }
    @Test fun exactBottomAfterGestureResumesFollowing() {
        assertTrue(layout().shouldSettle)
        assertTrue(layout().atBottom)
    }
    @Test fun fingerLiftDoesNotSettleAnActiveFling() {
        assertFalse(layout(scrolling = true).shouldSettle)
    }
    @Test fun activeDragCannotResumeAtBottom() {
        assertFalse(layout(dragging = true).shouldSettle)
        assertFalse(layout(dragging = true, scrolling = true).shouldSettle)
    }
    @Test fun programmaticScrollAndLayoutResizeCannotResumeFollowing() {
        assertFalse(layout(pending = false).shouldSettle)
        assertFalse(layout(offset = 100, pending = false).shouldSettle)
        assertFalse(layout(scrolling = true, pending = false).shouldSettle)
    }
    @Test fun emptyMeasurementDoesNotConsumePendingGesture() {
        assertFalse(layout(total = 0).shouldSettle)
    }
    @Test fun shortDragThenFlingAwayStaysPaused() {
        assertFalse(layout(offset = 3, scrolling = true).shouldSettle)
        val stopped = layout(offset = 90)
        assertTrue(stopped.shouldSettle)
        assertFalse(stopped.atBottom)
    }
    @Test fun returningFlingCanResumeOnlyAfterItStops() {
        assertFalse(layout(scrolling = true).shouldSettle)
        assertTrue(layout().shouldSettle && layout().atBottom)
    }
    private fun source(name: String): String = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .map { File(it, "com/openminis/app/ui/chat/$name.kt") }.first { it.isFile }
        .readText().replace("\r\n", "\n")

    @Test fun dragImmediatelyPausesAndPassiveResetPathsAreRemoved() {
        val screen = source("ChatScreen")
        val drag = screen.substringAfter("is androidx.compose.foundation.interaction.DragInteraction.Start -> {")
            .substringBefore("is androidx.compose.foundation.interaction.DragInteraction.Stop")
        assertTrue(drag.contains("userScrolledAway = true"))
        assertFalse(screen.contains("LaunchedEffect(isNearBottom.value)"))
        assertFalse(screen.contains("LaunchedEffect(imeBottomPx)"))
        assertFalse(screen.contains("tracedScrollToItem(\"settle-after-interaction\""))
        assertTrue(screen.contains("fun currentGestureLayout() = ChatScrollGestureLayout("))
        assertTrue(screen.contains("val resumedAtBottom = settled.atBottom &&"))
        assertTrue(screen.contains("if (viewModel.isStreaming.value && manualDragLeftBottom)"))
        assertTrue(screen.contains("val resumedAtBottom = settled.atBottom && !manualDragLeftBottom"))
        assertTrue(screen.contains("userScrolledAway = !resumedAtBottom"))
    }
    @Test fun delayedCorrectionsRespectTheNewPause() {
        val screen = source("ChatScreen")
        for (reason in listOf("SEND-PATH/settle", "RESUME-BANNER/settle",
            "FAB-DOWN/settle", "SEND-PATH(keyboard-imeAction)/settle")) {
            val before = screen.substringBefore("tracedScrollToItem(\"$reason\"")
            assertTrue("Unguarded correction: $reason", before.takeLast(120).contains("if (!userScrolledAway)"))
        }
        assertTrue(screen.contains("kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) throw it"))
        assertTrue(screen.contains("LaunchedEffect(messages.lastOrNull()?.id)"))
    }
    @Test fun automaticCompactionCannotClearHistoryReadingIntent() {
        val screen = source("ChatScreen")
        val compact = screen.substringAfter("var previousRun: Long? = null")
            .substringBefore("else if (finished)")
        val resetAt = compact.indexOf("userScrolledAway = false")
        val gateAt = compact.indexOf("progress?.userInitiated == true")
        assertTrue(gateAt >= 0 && resetAt > gateAt)
        assertTrue(source("ChatViewModel").contains("userInitiated = !allowDuringProcessing"))
    }
}
