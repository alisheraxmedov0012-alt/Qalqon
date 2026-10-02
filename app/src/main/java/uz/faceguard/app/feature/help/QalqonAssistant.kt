package uz.faceguard.app.feature.help

import androidx.annotation.StringRes
import java.util.Locale
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
        val tokens = tokenize(question)
        if (tokens.isEmpty()) return AssistantReply.NotFound

        val hasDomainTerm = knowledge.domainTerms.any { term -> question.lowercase(Locale.ROOT).contains(term) }
        val best = documents.maxByOrNull { it.score(tokens) }
        val bestScore = best?.score(tokens) ?: 0

        if (bestScore == 0) {
            // No knowledge-base entry matched at all. If it did not even mention a
            // QALQON concept, it is an unrelated question and must be refused.
            return if (hasDomainTerm) AssistantReply.NotFound else AssistantReply.OutOfDomain
        }

        contextualReply(question, context, tokens)?.let { return it }

        return AssistantReply.Knowledge(
            titleRes = best!!.titleRes,
            bodyRes = best.bodyRes,
            kind = best.kind,
        )
    }

    /**
     * A small, explicit set of context-aware answers, offered only for an in-domain
     * question that is clearly about that topic. Falls back to the knowledge base when
     * the context does not fit.
     */
    private fun contextualReply(
        question: String,
        context: QalqonAssistantContext?,
        tokens: Set<String>,
    ): AssistantReply? {
        if (context == null) return null
        val q = question.lowercase(Locale.ROOT)
        fun mentions(vararg needles: String) = needles.any { it in q || it in tokens }

        return when {
            mentions("himoya", "protection", "защит") &&
                (!context.protectionEnabled && mentions("o'chir", "ochir", "off", "islamay", "ishlamay", "не работ", "выключ")) ->
                AssistantReply.Contextual(
                    bodyRes = R.string.help_assistant_ctx_protection_off,
                    followUpRes = R.string.help_assistant_ctx_protection_off_followup,
                )

            mentions("ilova", "app", "приложен") && context.protectedAppsCount == 0 ->
                AssistantReply.Contextual(
                    bodyRes = R.string.help_assistant_ctx_no_apps,
                    followUpRes = R.string.help_assistant_ctx_no_apps_followup,
                )

            mentions("bola", "child", "ребен", "ребён") && !context.hasChildren ->
                AssistantReply.Contextual(
                    bodyRes = R.string.help_assistant_ctx_no_children,
                    followUpRes = R.string.help_assistant_ctx_no_children_followup,
                )

            mentions("bildirishnoma", "notification", "уведомл") && !context.notificationsEnabled ->
                AssistantReply.Contextual(
                    bodyRes = R.string.help_assistant_ctx_notifications_off,
                )

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
                    haystack = (text.text(article.titleRes) + " " + text.text(article.bodyRes)).lowercase(Locale.ROOT),
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
                    haystack = (text.text(faq.questionRes) + " " + text.text(faq.answerRes)).lowercase(Locale.ROOT),
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
                    haystack = (text.text(item.questionRes) + " " + text.text(item.bodyRes)).lowercase(Locale.ROOT),
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
        fun score(tokens: Set<String>): Int = tokens.count { it in haystack }
    }

    private companion object {
        /** Very common words that carry no topical signal in any of the three languages. */
        val STOPWORDS = setOf(
            "va", "the", "and", "for", "how", "what", "why", "qanday", "nega", "nima",
            "men", "i", "you", "can", "do", "does", "is", "are", "it", "this", "that",
            "in", "on", "to", "of", "a", "an", "yordam", "ber", "bering", "kerak",
            "как", "что", "почему", "и", "в", "на", "не", "мне", "это", "ли",
        )

        fun tokenize(question: String): Set<String> =
            question.lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}']+"))
                .filter { it.length >= 3 && it !in STOPWORDS }
                .toSet()
    }
}
