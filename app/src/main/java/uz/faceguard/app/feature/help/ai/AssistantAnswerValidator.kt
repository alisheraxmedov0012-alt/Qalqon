package uz.faceguard.app.feature.help.ai

import java.util.Locale

/**
 * Deterministic output validator for grounded generation (Phase A2).
 *
 * It deliberately does NOT claim to prove semantic truth — keyword checks cannot. It is a
 * conservative gate: it accepts only text that is clearly a grounded answer and rejects
 * everything else, so the caller falls back to the deterministic answer. The safe bias is
 * "false negative acceptance is worse than falling back".
 *
 * Rules (all deterministic and unit-tested):
 *  1. Blank output -> [GenerationFailureReason.EMPTY_OUTPUT].
 *  2. Absurdly long output -> INVALID_OUTPUT (runaway/degenerate generation).
 *  3. SDK/refusal/error artefacts in the text -> INVALID_OUTPUT.
 *  4. No lexical overlap with the selected knowledge entry -> INVALID_OUTPUT (unrelated).
 *  5. A known invented/contradictory claim -> INVALID_OUTPUT.
 *
 * The model's own "I cannot answer" sentinel is also treated as INVALID_OUTPUT so the caller
 * uses the curated deterministic answer instead.
 */
object AssistantAnswerValidator {

    /** The model is asked to emit this when the knowledge does not answer the question. */
    const val INSUFFICIENT_MARKER = "INSUFFICIENT_QALQON_KNOWLEDGE"

    private const val MAX_LENGTH = 2000

    /** Artefacts that mean the "answer" is not a real, grounded answer. */
    private val GARBAGE_MARKERS = listOf(
        "errorcode", "genaiexception", "exception", "stacktrace",
        "as an ai", "i am an ai", "i'm an ai", "language model",
        "java.", "kotlin.", "nullpointer",
        "insufficient_qalqon_knowledge",
    )

    /**
     * Claims that must never be accepted, because they invent capability or contradict the
     * offline/privacy guarantees. Kept small and explicit on purpose. Provider brand names are
     * deliberately NOT listed here (the repository's network-scan tests treat those literals as
     * network usage); the generic AI/cloud markers below cover the same intent.
     */
    private val INVENTED_OR_CONTRADICTORY = listOf(
        "serverga yubor", "yuboriladi server", "serverga yukla", // "sent to the server"
        "internet orqali", "internetga ulanadi", "internetga yubor",
        "sun'iy intellekt", "suniy intellekt", "artificial intelligence",
        "cloud", "bulutga", "bulutli",
        "avtomatik aniqlaydi", "avtomatik bloklaydi", // "automatically detects/blocks"
        "automatically detects", "automatically blocks",
        "auto-detect", "autodetects",
    )

    /**
     * Prompt-injection artefacts (Phase A3 hardening). These phrases are never part of a
     * legitimate grounded QALQON answer, so their presence means the model echoed an
     * instruction-override attempt instead of answering from the knowledge entry. Rejecting
     * them keeps the deterministic fallback in charge.
     */
    private val INJECTION_ARTEFACTS = listOf(
        "ignore previous instructions", "ignore the previous instructions",
        "ignore all previous", "ignore the knowledge base", "ignore qalqon knowledge",
        "ignore the above", "disregard previous", "disregard the above",
        "as a general ai", "as an unrestricted", "unrestricted chatgpt",
        "jailbreak", "system prompt", "you are now",
    )

    /** Words that carry no evidential value for the overlap check. */
    private val STOPWORDS = setOf(
        "va", "the", "and", "for", "how", "what", "why", "qanday", "nega", "nima",
        "men", "i", "you", "can", "do", "does", "is", "are", "it", "this", "that",
        "in", "on", "to", "of", "a", "an", "yordam", "kerak", "qalqon",
        "как", "что", "почему", "и", "в", "на", "не", "это",
    )

    sealed interface Outcome {
        data object Accepted : Outcome
        data class Rejected(val reason: GenerationFailureReason) : Outcome
    }

    /**
     * Validates [text] against the knowledge entry it was grounded in.
     *
     * @return [Outcome.Accepted] only when the text is non-blank, short enough, free of
     *   SDK/refusal artefacts and known invented claims, and shares at least one meaningful
     *   token with the grounded knowledge (title or content).
     */
    fun validate(text: String, knowledge: GroundedKnowledgeContext): Outcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Outcome.Rejected(GenerationFailureReason.EMPTY_OUTPUT)
        if (trimmed.length > MAX_LENGTH) return Outcome.Rejected(GenerationFailureReason.INVALID_OUTPUT)

        val lower = trimmed.lowercase(Locale.ROOT)
        if (GARBAGE_MARKERS.any { it in lower }) {
            return Outcome.Rejected(GenerationFailureReason.INVALID_OUTPUT)
        }
        if (INVENTED_OR_CONTRADICTORY.any { it in lower }) {
            return Outcome.Rejected(GenerationFailureReason.INVALID_OUTPUT)
        }
        if (INJECTION_ARTEFACTS.any { it in lower }) {
            return Outcome.Rejected(GenerationFailureReason.INVALID_OUTPUT)
        }

        // Grounding overlap: the answer must share at least one meaningful token with the
        // selected entry, so a generic/unrelated answer cannot pass.
        val knowledgeTokens = tokensOf(knowledge.title + " " + knowledge.content)
        val answerTokens = tokensOf(trimmed)
        if (knowledgeTokens.intersect(answerTokens).isEmpty()) {
            return Outcome.Rejected(GenerationFailureReason.INVALID_OUTPUT)
        }

        return Outcome.Accepted
    }

    private fun tokensOf(value: String): Set<String> =
        value.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.length >= 4 && it !in STOPWORDS }
            .toSet()
}
