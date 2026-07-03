package com.qcgo.quality

/**
 * Tiny in-memory hand-off between the capture screen and the result screen.
 * Holds the most recent scan (including item thumbnail bitmaps). A single-shot
 * flow, so one slot is enough; each new scan replaces the previous one.
 */
object ResultStore {
    @Volatile
    var latest: FrameResult? = null
}
