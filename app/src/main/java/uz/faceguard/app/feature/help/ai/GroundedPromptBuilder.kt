package uz.faceguard.app.feature.help.ai

/**
 * Builds the strict, deterministic grounded prompt sent to Gemini Nano.
 *
 * Design rules (Phase A2):
 *  - The knowledge base is the only source of truth; the model may not add facts.
 *  - The prompt contains ONLY: the user question, the intent label, the one selected
 *    knowledge entry (title + body) and the approved non-sensitive context flags.
 *  - No secrets, identifiers or any other QALQON data can appear: the builder's inputs are
 *    the only content, and the context object can only carry the four approved flags.
 *  - Structure is fixed, so the request is auditable and testable.
 *
 * This class is pure and SDK-free.
 */
object GroundedPromptBuilder {

    private const val RULE_LANGUAGE_FOLLOWS_QUESTION =
        "Answer in the same language as the user's question."

    fun build(
        question: String,
        intentLabel: String,
        knowledge: GroundedKnowledgeContext,
        context: GroundedAssistantContext,
    ): String = buildString {
        appendLine("You are the QALQON help assistant.")
        appendLine("QALQON is an offline, on-device parental-control app.")
        appendLine()
        appendLine("RULES:")
        appendLine("1. Answer ONLY about QALQON and ONLY using the QALQON KNOWLEDGE below.")
        appendLine("2. Do not invent features, settings, screens, permissions, limits or policies.")
        appendLine("3. Do not use any knowledge that is not in the QALQON KNOWLEDGE section.")
        appendLine("4. Do not mention AI, models, servers, the internet or data collection.")
        appendLine("5. If the QALQON KNOWLEDGE does not answer the question, reply exactly:")
        appendLine("   INSUFFICIENT_QALQON_KNOWLEDGE")
        appendLine("6. Be concise and factual. Maximum 6 sentences.")
        appendLine("7. $RULE_LANGUAGE_FOLLOWS_QUESTION")
        appendLine()
        appendLine("INTENT: ${intentLabel.ifBlank { "UNKNOWN" }}")
        appendLine()
        appendLine("QALQON KNOWLEDGE:")
        appendLine("Title: ${knowledge.title}")
        appendLine("Content: ${knowledge.content}")
        if (!context.isEmpty) {
            appendLine()
            appendLine("APPROVED CONTEXT (non-sensitive, factual):")
            context.protectionEnabled?.let { appendLine("- protection enabled: $it") }
            context.protectedAppsCount?.let { appendLine("- protected apps count: $it") }
            context.hasChildren?.let { appendLine("- a child profile exists: $it") }
            context.notificationsEnabled?.let { appendLine("- notifications enabled: $it") }
        }
        appendLine()
        appendLine("USER QUESTION: $question")
        appendLine()
        appendLine("ANSWER:")
    }
}
