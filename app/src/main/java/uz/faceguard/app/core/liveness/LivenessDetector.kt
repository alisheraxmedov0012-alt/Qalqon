package uz.faceguard.app.core.liveness

/**
 * Group 9: decides a [LivenessResult] from a window of per-frame evidence.
 *
 * Pure Kotlin, synchronous and side-effect free, so the whole liveness decision
 * is unit-testable on the JVM and the engine/runtime never contain the algorithm.
 */
fun interface LivenessDetector {
    fun detect(frames: List<LivenessFrame>): LivenessResult
}
