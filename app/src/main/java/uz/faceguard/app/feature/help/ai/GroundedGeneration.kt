package uz.faceguard.app.feature.help.ai

/**
 * QALQON Phase A2 — grounded generation domain contract.
 *
 * These types are SDK-free: nothing here imports `com.google.mlkit.genai.*`. Only
 * [MlKitGeminiNanoGateway] touches the ML Kit SDK.
 *
 * The QALQON knowledge base stays the source of truth; a model is only a language layer.
 * Therefore the generator is always *handed* the single selected knowledge entry, and every
 * request is bounded by it. There is no "AI knows QALQON" mode.
 */

/**
 * The one knowledge entry a generation is grounded in. Built from the deterministic
 * assistant's own answer, so the model can never be given a topic the knowledge base did not
 * already select.
 */
data class GroundedKnowledgeContext(
    /** Stable knowledge-base entry id (e.g. `apps_protect`), for auditing/validation. */
    val entryId: String,
    /** Localized title of the entry (the app's current language). */
    val title: String,
    /** Localized body of the entry — the only factual content the model may rely on. */
    val content: String,
)

/**
 * The non-sensitive runtime flags the model may see — and only the ones relevant to the
 * selected entry. A `null` field means "not relevant, not disclosed". No other QALQON state
 * can be expressed here by construction.
 */
data class GroundedAssistantContext(
    val protectionEnabled: Boolean? = null,
    val protectedAppsCount: Int? = null,
    val hasChildren: Boolean? = null,
    val notificationsEnabled: Boolean? = null,
) {
    /** True when no flag is disclosed. */
    val isEmpty: Boolean
        get() = protectionEnabled == null &&
            protectedAppsCount == null &&
            hasChildren == null &&
            notificationsEnabled == null
}

/** Why grounded generation did not yield usable text. Never carries SDK exception detail. */
enum class GenerationFailureReason {
    /** Gemini Nano is not usable right now (downloadable/downloading/unavailable). */
    UNAVAILABLE,

    /** The SDK could not be queried (no AICore, initialization failure). */
    SDK_ERROR,

    /** The model call itself failed. */
    GENERATION_ERROR,

    /** The model returned text that failed deterministic validation. */
    INVALID_OUTPUT,

    /** The model returned nothing usable. */
    EMPTY_OUTPUT,

    /** No grounding content was available, so generation is not attempted. */
    NO_GROUNDING,
}

/** The outcome of a grounded generation attempt. */
sealed interface GenerationResult {
    /** Validated, grounded natural-language text (already trimmed). */
    data class Success(val text: String) : GenerationResult

    /** No usable text; the caller must use the deterministic answer. */
    data class Failure(val reason: GenerationFailureReason) : GenerationResult
}

/**
 * Produces a grounded natural-language rendering of a QALQON knowledge entry.
 *
 * Implementations must: gate on [GeminiNanoStatus.AVAILABLE], build a strict grounded prompt,
 * call the model, and validate the output — returning [GenerationResult.Failure] on any
 * doubt. They never mutate QALQON state and never receive sensitive data (the parameters are
 * the only inputs).
 */
interface GroundedGenerator {
    suspend fun generate(
        question: String,
        intentLabel: String,
        knowledge: GroundedKnowledgeContext,
        context: GroundedAssistantContext,
    ): GenerationResult
}

/**
 * The narrow SDK-facing seam for text generation. `String` in, `String` out — no ML Kit types
 * leak into the domain. Implemented by [MlKitGeminiNanoGateway].
 */
interface GeminiNanoGenerator {
    /** Sends [prompt] to the on-device model and returns its raw text (may be empty). */
    suspend fun generate(prompt: String): String
}
