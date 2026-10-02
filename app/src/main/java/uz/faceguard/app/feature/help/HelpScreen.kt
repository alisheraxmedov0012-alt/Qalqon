package uz.faceguard.app.feature.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.faceguard.app.R
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.qalqon.QalqonCard
import uz.faceguard.app.core.ui.qalqon.QalqonSectionHeader

/**
 * Settings → Yordam: the QALQON Help Center.
 *
 * A localized, fully offline help surface: an intro, the Qalqon Assistant entry, a
 * local search over the knowledge base, the guide grouped by category, expandable
 * FAQs and a troubleshooting section. All content comes from [QalqonKnowledgeBase];
 * the screen owns presentation only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    onOpenAssistant: () -> Unit,
    viewModel: HelpViewModel = hiltViewModel(),
) {
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            // Reuses the app's existing localized "Back" action label.
                            contentDescription = stringResource(R.string.request_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = QalqonDimens.screenPadding,
                end = QalqonDimens.screenPadding,
                top = QalqonDimens.spacing.lg,
                bottom = QalqonDimens.spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
        ) {
            item {
                Text(
                    stringResource(R.string.help_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { AssistantEntry(onOpen = onOpenAssistant) }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.onQueryChange(it)
                    },
                    label = { Text(stringResource(R.string.help_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when (val state = searchState) {
                HelpSearchState.Browsing -> guideItems()
                is HelpSearchState.Results ->
                    if (state.matches.isEmpty) {
                        item {
                            Text(
                                stringResource(R.string.help_search_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        resultItems(state.matches)
                    }
                HelpSearchState.Searching -> Unit
            }

            item {
                Text(
                    stringResource(R.string.help_version, stringResource(R.string.app_name)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = QalqonDimens.spacing.md),
                )
            }
        }
    }
}

/** The prominent Assistant entry point. */
@Composable
private fun AssistantEntry(onOpen: () -> Unit) {
    QalqonCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = QalqonDimens.spacing.md),
                verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.xs),
            ) {
                Text(
                    stringResource(R.string.help_assistant_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.help_assistant_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The full guide: categories with their articles, then FAQ and troubleshooting. */
private fun LazyListScope.guideItems() {
    HelpCategory.entries.forEach { category ->
        if (category == HelpCategory.TROUBLESHOOTING) return@forEach
        val articles = QalqonKnowledgeBase.articles.filter { it.categoryRes == category.labelRes }
        if (articles.isEmpty()) return@forEach
        item(key = "cat_${category.name}") {
            QalqonSectionHeader(title = stringResource(category.labelRes))
        }
        items(articles, key = { it.id }) { article ->
            ExpandableRow(
                title = stringResource(article.titleRes),
                body = stringResource(article.bodyRes),
            )
        }
    }

    item(key = "faq_header") {
        QalqonSectionHeader(title = stringResource(R.string.help_section_faq))
    }
    items(QalqonKnowledgeBase.faq, key = { it.id }) { faq ->
        ExpandableRow(
            title = stringResource(faq.questionRes),
            body = stringResource(faq.answerRes),
        )
    }

    item(key = "ts_header") {
        QalqonSectionHeader(title = stringResource(R.string.help_section_troubleshooting))
    }
    items(QalqonKnowledgeBase.troubleshooting, key = { it.id }) { entry ->
        ExpandableRow(
            title = stringResource(entry.questionRes),
            body = stringResource(entry.bodyRes),
        )
    }
}

/** Search results, in the same order the guide uses. */
private fun LazyListScope.resultItems(matches: HelpSearchMatches) {
    items(
        QalqonKnowledgeBase.articles.filter { it.id in matches.articleIds },
        key = { it.id },
    ) { article ->
        ExpandableRow(
            title = stringResource(article.titleRes),
            body = stringResource(article.bodyRes),
        )
    }
    items(
        QalqonKnowledgeBase.faq.filter { it.id in matches.faqIds },
        key = { it.id },
    ) { entry ->
        ExpandableRow(
            title = stringResource(entry.questionRes),
            body = stringResource(entry.answerRes),
        )
    }
    items(
        QalqonKnowledgeBase.troubleshooting.filter { it.id in matches.troubleshootingIds },
        key = { it.id },
    ) { entry ->
        ExpandableRow(
            title = stringResource(entry.questionRes),
            body = stringResource(entry.bodyRes),
        )
    }
}

/**
 * One collapsible help entry. The whole row is the (≥48 dp) touch target; the state is
 * exposed to accessibility through the trailing icon's description, so a screen reader
 * announces whether the entry expands or collapses.
 */
@Composable
private fun ExpandableRow(title: String, body: String) {
    var expanded by remember { mutableStateOf(false) }
    QalqonCard(modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Row(
            modifier = Modifier.heightIn(min = QalqonDimens.sizes.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(
                    if (expanded) R.string.help_entry_collapse else R.string.help_entry_expand,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
