package uz.faceguard.app.feature.help.ai

import uz.faceguard.app.feature.help.AssistantReply
import uz.faceguard.app.feature.help.HelpContent
import uz.faceguard.app.feature.help.HelpTextProvider
import uz.faceguard.app.feature.help.QalqonAssistant
import uz.faceguard.app.feature.help.QalqonAssistantContext
import uz.faceguard.app.feature.help.QalqonAssistantIntents
import uz.faceguard.app.feature.help.QalqonKnowledgeBase

/**
 * The QALQON assistant with an optional grounded generative layer (Phase A2).
 *
 * The deterministic [QalqonAssistant] is always run first and remains authoritative:
 *  1. Ask the deterministic assistant.
 *  2. If it did not answer from the knowledge base (refusal, "not found" or a context
 *     answer), return it unchanged — generation never runs for those.
 *  3. Otherwise hand the *selected knowledge entry* (and only the context flags relevant to
 *     it) to [GroundedGenerator].
 *  4. A validated [GenerationResult.Success] becomes [AssistantReply.Generated]; every other
 *     outcome returns the deterministic answer.
 *
 * So the deterministic answer is the total fallback: QALQON Help never depends on the model.
 * This class is SDK-free and takes no sensitive data.
 */
class GroundedQalqonAssistant(
    private val deterministic: QalqonAssistant,
    private val generator: GroundedGenerator,
    private val text: HelpTextProvider,
    private val knowledge: HelpContent = QalqonKnowledgeBase,
) : QalqonAssistant {

    override suspend fun ask(question: String, context: QalqonAssistantContext?): AssistantReply {
        val base = deterministic.ask(question, context)
        if (base !is AssistantReply.Knowledge) return base

        val grounded = groundedContextFor(base)
        val intentLabel = entryIdFor(base)?.let { QalqonAssistantIntents.intentLabelForEntry(it) }.orEmpty()
        val approved = approvedContextFor(intentLabel, context)

        return when (val result = generator.generate(question, intentLabel, grounded, approved)) {
            is GenerationResult.Success -> AssistantReply.Generated(result.text)
            is GenerationResult.Failure -> base
        }
    }

    /** The single knowledge entry a deterministic Knowledge answer came from, if resolvable. */
    private fun entryIdFor(reply: AssistantReply.Knowledge): String? =
        knowledge.articles.firstOrNull { it.titleRes == reply.titleRes && it.bodyRes == reply.bodyRes }?.id
            ?: knowledge.faq.firstOrNull { it.questionRes == reply.titleRes && it.answerRes == reply.bodyRes }?.id
            ?: knowledge.troubleshooting.firstOrNull { it.questionRes == reply.titleRes && it.bodyRes == reply.bodyRes }?.id

    private fun groundedContextFor(reply: AssistantReply.Knowledge): GroundedKnowledgeContext =
        GroundedKnowledgeContext(
            entryId = entryIdFor(reply) ?: "res:${reply.titleRes}",
            title = text.text(reply.titleRes),
            content = text.text(reply.bodyRes),
        )

    /**
     * Only the context flag that is relevant to the selected intent is disclosed; every other
     * flag stays null. There is no way to express any other QALQON state here.
     */
    private fun approvedContextFor(
        intentLabel: String,
        context: QalqonAssistantContext?,
    ): GroundedAssistantContext {
        if (context == null) return GroundedAssistantContext()
        return when (intentLabel) {
            "PROTECTION_STATUS" -> GroundedAssistantContext(protectionEnabled = context.protectionEnabled)
            "PROTECTED_APPS" -> GroundedAssistantContext(protectedAppsCount = context.protectedAppsCount)
            "CHILDREN" -> GroundedAssistantContext(hasChildren = context.hasChildren)
            "NOTIFICATIONS_REQUESTS" -> GroundedAssistantContext(notificationsEnabled = context.notificationsEnabled)
            else -> GroundedAssistantContext()
        }
    }
}
