package com.openminis.app.ui.chat

import android.content.Context
import android.provider.Settings

/**
 * Runtime switches for chat-scroll diagnosis, driven over adb without a
 * rebuild (settings are world-readable and shell-writable, unlike system
 * properties which need hidden API reflection):
 *
 * ```
 * adb shell settings put global debug_minis_scroll trace,frames
 * adb shell settings put global debug_minis_scroll trace,frames,nopin
 * adb shell settings delete global debug_minis_scroll
 * ```
 *
 * Tokens, order-free:
 *   trace      — log every programmatic viewport mutation and every
 *                `userScrolledAway` flip (ScrollSrc / ScrollFollow tags)
 *   frames     — one position snapshot per layout change (ScrollFrame tag);
 *                also the on-device probe for the offset/scrollOffset
 *                coordinate convention under reverseLayout
 *   nopin      — disable the BottomFollowLayout bottom pin
 *   anchor     — enable the measure-frame reading-anchor compensation
 *                (detached-reading growth absorption, ReadingAnchor.kt);
 *                opt-in because its behavior changes how the viewport
 *                reacts to streaming growth while the reader is away
 *   notrailing — disable the trailing-row follow scroll
 *   noreserve  — disable the reserve-change follow scroll
 *   all        — shorthand for trace,frames
 *
 * With no setting present every switch is off and behaviour is exactly
 * stock. Parsed values are cached with a short TTL so the per-frame readers
 * (snapshotFlow bodies) never pay a binder call; an adb write lands within
 * about a second of the next layout pass.
 */
object ScrollDebugFlags {
    private const val KEY = "debug_minis_scroll"
    private const val TTL_MS = 1500L

    @Volatile private var appContext: Context? = null
    @Volatile private var parsed: Parsed = Parsed.STOCK
    @Volatile private var parsedAtMs = 0L

    private data class Parsed(
        val trace: Boolean,
        val frames: Boolean,
        val pinEnabled: Boolean,
        val anchorEnabled: Boolean,
        val trailingEnabled: Boolean,
        val reserveEnabled: Boolean,
    ) {
        companion object {
            // No setting → stock behaviour: telemetry off, every writer on.
            val STOCK = Parsed(false, false, true, true, true, true)
        }
    }

    /** Bind once with any context; only the application context is retained. */
    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    val traceMoves: Boolean get() = cached().trace
    val frameSnapshots: Boolean get() = cached().frames
    val pinFollowEnabled: Boolean get() = cached().pinEnabled
    val readingAnchorEnabled: Boolean get() = cached().anchorEnabled
    val trailingRowEnabled: Boolean get() = cached().trailingEnabled
    val reserveChangeEnabled: Boolean get() = cached().reserveEnabled

    private fun cached(): Parsed {
        val now = System.currentTimeMillis()
        if (now - parsedAtMs > TTL_MS) {
            parsedAtMs = now
            val context = appContext
            parsed = if (context == null) {
                Parsed.STOCK
            } else {
                val raw = runCatching {
                    Settings.Global.getString(context.contentResolver, KEY)
                }.getOrNull()
                if (raw.isNullOrBlank()) Parsed.STOCK else parse(raw)
            }
        }
        return parsed
    }

    private fun parse(raw: String): Parsed {
        val tokens = raw.lowercase()
            .split(',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return Parsed(
            trace = "trace" in tokens || "all" in tokens,
            frames = "frames" in tokens || "all" in tokens,
            pinEnabled = "nopin" !in tokens,
            anchorEnabled = "anchor" in tokens || "all" in tokens,
            trailingEnabled = "notrailing" !in tokens,
            reserveEnabled = "noreserve" !in tokens,
        )
    }
}

/**
 * One layout observation of the chat LazyColumn, logged verbatim by the
 * `frames` switch. Deliberately flat and printable so the log line can be
 * diffed line-by-line against a screen recording.
 *
 * `anchor*` describes the row the scroll position glues (index equal to
 * firstVisibleItemIndex); `low*` describes the first-listed visible item,
 * which during a live-edge insertion can be a lower, partially clipped row
 * rather than the anchor. Measured convention: an item's [offset] field
 * equals minus firstVisibleItemScrollOffset whenever the item IS the anchor.
 */
internal data class ScrollFrameSnapshot(
    val anchorIdx: Int,
    val anchorOff: Int,
    val anchorKey: String,
    val anchorOffset: Int,
    val anchorSize: Int,
    val lowIdx: Int,
    val lowKey: String,
    val lowOffset: Int,
    val viewportStart: Int,
    val viewportEnd: Int,
    val beforePadding: Int,
    val afterPadding: Int,
    val totalItems: Int,
    val inProgress: Boolean,
    val streaming: Boolean,
    val detached: Boolean,
) {
    override fun toString(): String =
        "anchor(idx=$anchorIdx off=$anchorOff key=$anchorKey offset=$anchorOffset size=$anchorSize) " +
            "low(idx=$lowIdx key=$lowKey offset=$lowOffset) " +
            "viewport=[$viewportStart,$viewportEnd] pad=[before=$beforePadding,after=$afterPadding] " +
            "total=$totalItems inProgress=$inProgress streaming=$streaming detached=$detached"
}

