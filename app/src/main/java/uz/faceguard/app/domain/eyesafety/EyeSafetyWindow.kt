package uz.faceguard.app.domain.eyesafety

/**
 * Phase 6 Step 1: the temporal window an eye-safety decision is made over.
 *
 * Deliberately separate from the recognition engine's debounce/confirmation/frame-TTL: this buffer
 * only holds compact per-frame evidence and never changes those semantics. It is also separate
 * from [EyeSafetyFrame]'s contract, so the window can be reasoned about (and tested) on its own.
 *
 * Two rules keep the evidence honest:
 *
 *  - frames must arrive in **strictly increasing** timestamp order — a duplicate or out-of-order
 *    frame is rejected rather than inserted, so the same observation can never be counted twice
 *    and a replayed frame cannot rewind the window;
 *  - frames older than [maxAgeMs] relative to the newest observation are dropped, so a signal
 *    **decays** instead of latching on stale evidence when frames stop arriving (which is the
 *    normal case here: the camera only runs while a scan window is open).
 *
 * No Android, no Room, no coroutines and no timer: the caller supplies `now`.
 */
class EyeSafetyWindow(
    private val maxAgeMs: Long,
    private val maxFrames: Int,
) {

    private val frames = ArrayDeque<EyeSafetyFrame>()

    val size: Int get() = frames.size

    fun isEmpty(): Boolean = frames.isEmpty()

    /** Adds [frame]; false when it is a duplicate or out-of-order frame. */
    fun add(frame: EyeSafetyFrame): Boolean {
        val last = frames.lastOrNull()
        if (last != null && frame.timestampMs <= last.timestampMs) return false
        frames.addLast(frame)
        while (frames.size > maxFrames) frames.removeFirst()
        trim(frame.timestampMs)
        return true
    }

    /**
     * Drops frames older than [maxAgeMs] relative to [now] and returns what is left. Called by
     * [add] and by [snapshot] so an idle pipeline (no new frames) still decays to an empty window
     * instead of reporting a stale state forever.
     */
    fun snapshot(now: Long): List<EyeSafetyFrame> {
        trim(now)
        return frames.toList()
    }

    fun clear() {
        frames.clear()
    }

    private fun trim(now: Long) {
        val cutoff = now - maxAgeMs
        while (frames.isNotEmpty() && frames.first().timestampMs < cutoff) frames.removeFirst()
    }
}
