package uz.faceguard.app.feature.help

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import uz.faceguard.app.core.i18n.AppLocale
import uz.faceguard.app.data.prefs.AppLanguageStore

/** The ids that matched a help search, grouped so the UI can render them in order. */
data class HelpSearchMatches(
    val articleIds: List<String> = emptyList(),
    val faqIds: List<String> = emptyList(),
    val troubleshootingIds: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = articleIds.isEmpty() && faqIds.isEmpty() && troubleshootingIds.isEmpty()
}

/** What the Help Center shows for the current query. */
sealed interface HelpSearchState {
    /** No query: show the full guide. */
    data object Browsing : HelpSearchState

    /** The query is being evaluated. */
    data object Searching : HelpSearchState

    data class Results(val matches: HelpSearchMatches) : HelpSearchState
}

/**
 * The Help Center's search.
 *
 * Everything is local: the knowledge base's own localized text is indexed in memory on
 * a background dispatcher and matched by normalized token overlap across the article
 * titles and bodies, the FAQ questions and answers, and the troubleshooting entries.
 * No network, no disk, and nothing is persisted — the index is rebuilt when the
 * selected language changes, so a search in Uzbek never matches English text.
 */
@HiltViewModel
class HelpViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val languageStore: AppLanguageStore,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val searchState: StateFlow<HelpSearchState> = query
        .map { text ->
            if (text.isBlank()) {
                HelpSearchState.Browsing
            } else {
                HelpSearchState.Results(search(text))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HelpSearchState.Browsing)

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Matches the query against the localized knowledge base. Pure in-memory work. */
    private suspend fun search(text: String): HelpSearchMatches = withContext(Dispatchers.Default) {
        val strings = localizedStrings()
        val tokens = text.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.length >= 2 }
            .toSet()
        if (tokens.isEmpty()) return@withContext HelpSearchMatches()

        fun matches(haystack: String): Boolean {
            val lower = haystack.lowercase(Locale.ROOT)
            return tokens.all { it in lower }
        }

        HelpSearchMatches(
            articleIds = QalqonKnowledgeBase.articles
                .filter { matches(strings(it.titleRes) + " " + strings(it.bodyRes)) }
                .map { it.id },
            faqIds = QalqonKnowledgeBase.faq
                .filter { matches(strings(it.questionRes) + " " + strings(it.answerRes)) }
                .map { it.id },
            troubleshootingIds = QalqonKnowledgeBase.troubleshooting
                .filter { matches(strings(it.questionRes) + " " + strings(it.bodyRes)) }
                .map { it.id },
        )
    }

    /** Resolver over the app's currently selected language, so search follows the UI language. */
    private fun localizedStrings(): (Int) -> String {
        val language = languageStore.current()
        val resources = language
            ?.let { AppLocale.localizedContext(context, it).resources }
            ?: context.resources
        return { res -> resources.getString(res) }
    }
}
