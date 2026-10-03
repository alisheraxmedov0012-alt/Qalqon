package uz.faceguard.app.feature.help.ai

/**
 * The grounded generation pipeline (Phase A2).
 *
 * Order of operations — the "correct" flow from the phase brief:
 *
 *   QUESTION -> capability gate -> grounded prompt -> on-device generation -> validation
 *            -> Success(text) | Failure(reason)
 *
 * Every non-[GeminiNanoStatus.AVAILABLE] state, any SDK/generation exception, and any
 * invalid/empty output yields [GenerationResult.Failure] so the caller uses the deterministic
 * answer. This class is SDK-free (it only knows the [GeminiNanoGenerator] seam), so it is
 * fully unit-testable on the JVM.
 */
class GroundedGeminiNanoGenerator(
    private val capability: GeminiNanoCapability,
    private val generator: GeminiNanoGenerator,
    private val promptBuilder: GroundedPromptBuilder = GroundedPromptBuilder,
) : GroundedGenerator {

    override suspend fun generate(
        question: String,
        intentLabel: String,
        knowledge: GroundedKnowledgeContext,
        context: GroundedAssistantContext,
    ): GenerationResult {
        // 1. Availability gate: only AVAILABLE may enter the generative path.
        when (capability.detect()) {
            GeminiNanoStatus.AVAILABLE -> Unit
            GeminiNanoStatus.SDK_ERROR -> return GenerationResult.Failure(GenerationFailureReason.SDK_ERROR)
            GeminiNanoStatus.DOWNLOADABLE,
            GeminiNanoStatus.DOWNLOADING,
            GeminiNanoStatus.UNAVAILABLE,
            -> return GenerationResult.Failure(GenerationFailureReason.UNAVAILABLE)
        }

        // 2. No grounding content -> do not ask the model to invent something.
        if (knowledge.title.isBlank() && knowledge.content.isBlank()) {
            return GenerationResult.Failure(GenerationFailureReason.NO_GROUNDING)
        }

        // 3. Build the strict grounded prompt and call the model.
        val prompt = promptBuilder.build(question, intentLabel, knowledge, context)
        val raw = runCatching { generator.generate(prompt) }
            .getOrElse { return GenerationResult.Failure(GenerationFailureReason.GENERATION_ERROR) }

        // 4. Deterministic validation; anything doubtful falls back.
        return when (val outcome = AssistantAnswerValidator.validate(raw, knowledge)) {
            AssistantAnswerValidator.Outcome.Accepted ->
                GenerationResult.Success(raw.trim())
            is AssistantAnswerValidator.Outcome.Rejected ->
                GenerationResult.Failure(outcome.reason)
        }
    }
}
