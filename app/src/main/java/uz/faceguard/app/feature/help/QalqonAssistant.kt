package uz.faceguard.app.feature.help

import androidx.annotation.StringRes
import uz.faceguard.app.R

/**
 * QALQON's assistant contract.
 *
 * IMPORTANT — honest capability statement: the repository contains **no on-device
 * language model** (its only inference asset is the face-recognition
 * `mobile_face_net.tflite`), and QALQON is offline-first with no network client. So
 * this is **not** generative AI: [LocalQalqonKnowledgeAssistant] is a deterministic,
 * domain-bounded retrieval/intent assistant that answers *only* from
 * [QalqonKnowledgeBase] and refuses everything else. It is deliberately behind this
 * interface so a real on-device model can replace it later without touching the Help
 * Center UI.
 *
 * The assistant never sees PINs, templates, embeddings or any stored user data: its
 * only inputs are the typed question and an optional [QalqonAssistantContext] of
 * non-sensitive runtime flags.
 */
interface QalqonAssistant {
    suspend fun ask(question: String, context: QalqonAssistantContext? = null): AssistantReply
}

/**
 * The limited, **non-sensitive** runtime state the assistant may use for
 * context-aware answers. Never contains secrets or identifiers.
 */
data class QalqonAssistantContext(
    val protectionEnabled: Boolean,
    val protectedAppsCount: Int,
    val hasChildren: Boolean,
    val notificationsEnabled: Boolean,
)

/** What the assistant replies with. Every answer is a localized resource id. */
sealed interface AssistantReply {
    /** An answer taken verbatim from the knowledge base. */
    data class Knowledge(
        @StringRes val titleRes: Int,
        @StringRes val bodyRes: Int,
        val kind: HelpEntryKind,
    ) : AssistantReply

    /** An answer grounded in the (non-sensitive) runtime context. */
    data class Contextual(
        @StringRes val bodyRes: Int,
        @StringRes val followUpRes: Int? = null,
    ) : AssistantReply

    /** The question is not about QALQON: the assistant refuses. */
    data object OutOfDomain : AssistantReply

    /** In-domain, but the guide does not cover it: say so honestly. */
    data object NotFound : AssistantReply
}

/** Resolves a resource id to text in the app's selected language. */
fun interface HelpTextProvider {
    fun text(@StringRes res: Int): String
}

/**
 * The local, deterministic implementation.
 *
 * Routing is always QUESTION → INTENT → KNOWLEDGE MATCH → OPTIONAL CONTEXT → ANSWER (see
 * [QalqonAssistantIntents]): the intent is classified first, so a generic word can never
 * pull a question onto another topic's context answer.
 *
 * How the three mandatory guarantees are met:
 *  - **domain bound:** an answer is only produced when the question actually matches
 *    the QALQON knowledge base (or a QALQON term); otherwise the localized refusal is
 *    returned — the domain gate is in the code path, not in UI copy.
 *  - **no hallucination:** replies are *verbatim* knowledge-base resource ids; the
 *    assistant cannot compose a sentence, so it cannot invent a setting or a button.
 *  - **no outbound data:** matching runs entirely on-device over localized strings.
 */
class LocalQalqonKnowledgeAssistant(
    private val text: HelpTextProvider,
    private val knowledge: HelpContent = QalqonKnowledgeBase,
) : QalqonAssistant {

    /** Localized documents, built once per instance (the language is fixed per screen). */
    private val documents: List<Document> by lazy { buildDocuments() }

    override suspend fun ask(question: String, context: QalqonAssistantContext?): AssistantReply {
        // QUESTION → INTENT → KNOWLEDGE MATCH → OPTIONAL CONTEXT → ANSWER.
        val normalized = QalqonAssistantIntents.normalize(question)
        val tokens = tokenize(normalized)
        if (tokens.isEmpty()) return AssistantReply.NotFound

        val hasDomainTerm = knowledge.domainTerms.any { it in normalized }

        // 1. Classify the intent first. This is what keeps a generic word such as "ilova"
        //    from collapsing every question onto the same context answer.
        val match = QalqonAssistantIntents.classify(normalized, tokens)
        if (match != null) {
            // 2. Context is allowed only for the intent it belongs to, and only for an
            //    explicit "state" question — never merely because a flag is set.
            contextualReply(match.intent, normalized, context)?.let { return it }

            // 3. Otherwise answer from the knowledge entry that intent maps to.
            documents.firstOrNull { it.id == match.knowledgeId }?.let { target ->
                return AssistantReply.Knowledge(target.titleRes, target.bodyRes, target.kind)
            }
            // The knowledge source does not contain the mapped entry — this only happens
            // with a test fixture. Because the intent is already confident, answer with the
            // best retrieval here without applying the stricter unclassified threshold.
            documents.maxByOrNull { it.score(tokens) }
                ?.takeIf { it.score(tokens) > 0 }
                ?.let { return AssistantReply.Knowledge(it.titleRes, it.bodyRes, it.kind) }
        }

        // 4. No classified intent: fall back to retrieval over the knowledge base.
        val best = documents.maxByOrNull { it.score(tokens) }
        val bestScore = best?.score(tokens) ?: 0
        if (bestScore < QalqonAssistantIntents.FALLBACK_MIN_OVERLAP || !hasDomainTerm) {
            // Either nothing matched, or the only overlap was an incidental generic word
            // ("kod" in "Python kod yoz", "telefon" in an unrelated sentence). Without a
            // QALQON concept the question is unrelated and must be refused; with one, say
            // honestly that the guide does not cover it.
            return if (hasDomainTerm) AssistantReply.NotFound else AssistantReply.OutOfDomain
        }

        return AssistantReply.Knowledge(
            titleRes = best!!.titleRes,
            bodyRes = best!!.bodyRes,
            kind = best.kind,
        )
    }

    /**
     * A small, explicit set of context-aware answers. Each is offered only for its own
     * intent *and* only when the question is explicitly about that state (see
     * [QalqonAssistantIntents]). A flag on its own never produces an answer: for example,
     * "Bu ilova nima haqida?" is an ABOUT question and is answered from the knowledge base
     * even when `protectedAppsCount == 0`.
     */
    private fun contextualReply(
        intent: AssistantIntent,
        normalized: String,
        context: QalqonAssistantContext?,
    ): AssistantReply? {
        if (context == null) return null
        fun about(phrases: List<String>) = QalqonAssistantIntents.matchesAny(normalized, phrases)

        return when (intent) {
            AssistantIntent.PROTECTION_STATUS ->
                if (!context.protectionEnabled &&
                    about(QalqonAssistantIntents.PROTECTION_OFF_CONTEXT)
                ) {
                    AssistantReply.Contextual(
                        bodyRes = R.string.help_assistant_ctx_protection_off,
                        followUpRes = R.string.help_assistant_ctx_protection_off_followup,
                    )
                } else {
                    null
                }

            AssistantIntent.PROTECTED_APPS ->
                if (context.protectedAppsCount == 0 &&
                    about(QalqonAssistantIntents.NO_APPS_CONTEXT)
                ) {
                    AssistantReply.Contextual(
                        bodyRes = R.string.help_assistant_ctx_no_apps,
                        followUpRes = R.string.help_assistant_ctx_no_apps_followup,
                    )
                } else {
                    null
                }

            AssistantIntent.CHILDREN ->
                if (!context.hasChildren &&
                    about(QalqonAssistantIntents.NO_CHILDREN_CONTEXT)
                ) {
                    AssistantReply.Contextual(
                        bodyRes = R.string.help_assistant_ctx_no_children,
                        followUpRes = R.string.help_assistant_ctx_no_children_followup,
                    )
                } else {
                    null
                }

            AssistantIntent.NOTIFICATIONS_REQUESTS ->
                if (!context.notificationsEnabled &&
                    about(QalqonAssistantIntents.NOTIFICATIONS_OFF_CONTEXT)
                ) {
                    AssistantReply.Contextual(
                        bodyRes = R.string.help_assistant_ctx_notifications_off,
                    )
                } else {
                    null
                }

            else -> null
        }
    }

    private fun buildDocuments(): List<Document> = buildList {
        knowledge.articles.forEach { article ->
            add(
                Document(
                    kind = HelpEntryKind.ARTICLE,
                    id = article.id,
                    titleRes = article.titleRes,
                    bodyRes = article.bodyRes,
                    haystack = QalqonAssistantIntents.normalize(
                        text.text(article.titleRes) + " " + text.text(article.bodyRes),
                    ),
                ),
            )
        }
        knowledge.faq.forEach { faq ->
            add(
                Document(
                    kind = HelpEntryKind.FAQ,
                    id = faq.id,
                    titleRes = faq.questionRes,
                    bodyRes = faq.answerRes,
                    haystack = QalqonAssistantIntents.normalize(
                        text.text(faq.questionRes) + " " + text.text(faq.answerRes),
                    ),
                ),
            )
        }
        knowledge.troubleshooting.forEach { item ->
            add(
                Document(
                    kind = HelpEntryKind.TROUBLESHOOTING,
                    id = item.id,
                    titleRes = item.questionRes,
                    bodyRes = item.bodyRes,
                    haystack = QalqonAssistantIntents.normalize(
                        text.text(item.questionRes) + " " + text.text(item.bodyRes),
                    ),
                ),
            )
        }
    }

    private data class Document(
        val kind: HelpEntryKind,
        val id: String,
        @StringRes val titleRes: Int,
        @StringRes val bodyRes: Int,
        val haystack: String,
    ) {
        fun score(tokens: List<String>): Int = tokens.count { it in haystack }
    }

    private companion object {
        /** Very common words that carry no topical signal in any of the three languages. */
        val STOPWORDS = setOf(
            "va", "the", "and", "for", "how", "what", "why", "qanday", "nega", "nima",
            "men", "i", "you", "can", "do", "does", "is", "are", "it", "this", "that",
            "in", "on", "to", "of", "a", "an", "yordam", "ber", "bering", "kerak",
            "как", "что", "почему", "и", "в", "на", "не", "мне", "это", "ли",
        )

        fun tokenize(normalized: String): List<String> =
            normalized
                .split(Regex("[^\\p{L}\\p{N}']+"))
                .filter { it.length >= 3 && it !in STOPWORDS }
    }
}
