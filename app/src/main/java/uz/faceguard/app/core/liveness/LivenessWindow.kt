package uz.faceguard.app.core.liveness

/**
 * Group 9: the temporal window a liveness decision is made over.
 *
 * Deliberately separate from the recognition engine's debounce / confirmation /
 * frame-TTL: this buffer only holds compact per-frame liveness evidence and never
 * changes those semantics. Frames must arrive in strictly increasing timestamp
 * order (duplicates and out-of-order frames are ignored), and frames older than
 * [maxAgeMs] relative to the newest observation are dropped so the signal decays
 * instead of latching on stale evidence.
 */
class LivenessWindow(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
) {
    private val frames = ArrayDeque<LivenessFrame>()

    val size: Int get() = frames.size

    fun isEmpty(): Boolean = frames.isEmpty()

    /** Adds [frame]; false when it is a duplicate/out-of-order frame. */
    fun add(frame: LivenessFrame): Boolean {
        val last = frames.lastOrNull()
        if (last != null && frame.timestamp <= last.timestamp) return false
        frames.addLast(frame)
        while (frames.size > maxFrames) frames.removeFirst()
        trim(frame.timestamp)
        return true
    }

    /**
     * Drops frames older than [maxAgeMs] relative to [now] and returns what is
     * left. Called by [add] and by [snapshot] so an idle pipeline (no new frames)
     * still decays to an empty window instead of reporting a stale state forever.
     */
    fun snapshot(now: Long = System.currentTimeMillis()): List<LivenessFrame> {
        trim(now)
        return frames.toList()
    }

    fun clear() {
        frames.clear()
    }

    private fun trim(now: Long) {
        val cutoff = now - maxAgeMs
        while (frames.isNotEmpty() && frames.first().timestamp < cutoff) frames.removeFirst()
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS = 2_500L
        const val DEFAULT_MAX_FRAMES = 24
    }
}
