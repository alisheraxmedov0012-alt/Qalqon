package uz.faceguard.app.feature.help.ai

/**
 * QALQON Phase A1 — Gemini Nano capability foundation.
 *
 * This package answers exactly one question: *is on-device generative AI usable on this
 * device right now?* It performs **capability detection only**. No generation, no prompt and
 * no user question ever reaches a model here, and nothing from QALQON's protected data
 * (PIN, biometrics, face templates/embeddings, Room/DataStore, child PII, activity history)
 * is referenced. The deterministic [uz.faceguard.app.feature.help.QalqonAssistant] remains
 * the only assistant that answers the user.
 *
 * The enum mirrors the ML Kit `@FeatureStatus` contract plus an explicit `SDK_ERROR` state so
 * that a device without AICore (or a thrown SDK exception) can never be mistaken for a
 * usable model.
 */
enum class GeminiNanoStatus {
    /** Gemini Nano is fully downloaded and ready to use (`FeatureStatus.AVAILABLE`). */
    AVAILABLE,

    /** Gemini Nano can be downloaded on request (`FeatureStatus.DOWNLOADABLE`). */
    DOWNLOADABLE,

    /** Gemini Nano is currently being downloaded (`FeatureStatus.DOWNLOADING`). */
    DOWNLOADING,

    /** Gemini Nano is not supported on this device (`FeatureStatus.UNAVAILABLE`). */
    UNAVAILABLE,

    /** The ML Kit GenAI SDK could not be queried (e.g. no AICore, thrown exception). */
    SDK_ERROR,
}

/**
 * Where an assistant request should be answered. Everything except [GENERATIVE_MODEL] must
 * be served by the existing deterministic assistant, so the fallback is total: there is no
 * state in which the user is left without an answer.
 */
enum class GenerationRoute {
    /** On-device Gemini Nano may be used (a future phase; not wired up in Phase A1). */
    GENERATIVE_MODEL,

    /** The existing deterministic, offline knowledge-base assistant answers. */
    DETERMINISTIC_FALLBACK,
}

/**
 * The narrow seam over the ML Kit GenAI SDK.
 *
 * Production uses [MlKitGeminiNanoGateway]; unit tests use a fake, so the capability logic is
 * verified on the JVM without AICore. The interface intentionally exposes only status,
 * optional warm-up and cleanup — never generation — so no user or QALQON data can flow into
 * the SDK through this layer.
 */
interface GeminiNanoGateway {
    /** Current model availability. Implementations may throw; [GeminiNanoCapability] catches. */
    suspend fun status(): GeminiNanoStatus

    /**
     * Loads the model and initializes runtime components ahead of a first request. Optional
     * for Phase A1; **never called from any production flow** (not at startup, not from the
     * assistant, not from a service). Must be invoked off the main thread (the SDK call is
     * suspending).
     */
    suspend fun warmup()

    /** Releases the SDK client. Safe to call when nothing was ever loaded. */
    fun close()
}

/**
 * Capability detection for on-device Gemini Nano.
 *
 * Pure and SDK-agnostic: it depends only on [GeminiNanoGateway], so it has no Android
 * dependency, touches no protected data, and cannot mutate any protection state.
 */
class GeminiNanoCapability(private val gateway: GeminiNanoGateway) {

    /**
     * Detects the current availability. Any failure is reported as [GeminiNanoStatus.SDK_ERROR]
     * rather than thrown, so a device without AICore degrades silently to the deterministic
     * assistant.
     */
    suspend fun detect(): GeminiNanoStatus =
        runCatching { gateway.status() }.getOrDefault(GeminiNanoStatus.SDK_ERROR)

    /** True only when Gemini Nano is fully available. */
    fun isGenerativeModelUsable(status: GeminiNanoStatus): Boolean =
        status == GeminiNanoStatus.AVAILABLE

    /** The route for the given status: only [GeminiNanoStatus.AVAILABLE] may use the model. */
    fun routeFor(status: GeminiNanoStatus): GenerationRoute =
        if (isGenerativeModelUsable(status)) GenerationRoute.GENERATIVE_MODEL
        else GenerationRoute.DETERMINISTIC_FALLBACK

    /** Convenience: detect, then decide the route. */
    suspend fun route(): GenerationRoute = routeFor(detect())

    /**
     * Best-effort model warm-up; never called by production code in Phase A1, and never
     * throws (a failed warm-up must not crash anything).
     */
    suspend fun warmup() {
        runCatching { gateway.warmup() }
    }

    /** Best-effort cleanup; never throws. */
    fun close() {
        runCatching { gateway.close() }
    }
}
