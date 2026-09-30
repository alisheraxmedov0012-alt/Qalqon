package uz.faceguard.app.domain.screentime

/**
 * Phase 4 Step 1B-3: a monotonic clock, for measuring how long something lasted.
 *
 * Wall-clock (`System.currentTimeMillis`) must never measure a duration: the user (or
 * the network) can move it, and a rollback would produce a negative or wildly wrong
 * elapsed time. This source only ever moves forward and never repeats, so
 * `end - start` is the real elapsed time. It says nothing about *when* something
 * happened — that still comes from the wall clock, which is separate.
 */
fun interface ElapsedTimeSource {
    /** Milliseconds since an arbitrary fixed origin; only differences are meaningful. */
    fun elapsedRealtimeMs(): Long
}
