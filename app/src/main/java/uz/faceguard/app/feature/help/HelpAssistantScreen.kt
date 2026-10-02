package uz.faceguard.app.feature.help

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.faceguard.app.R
import uz.faceguard.app.core.i18n.AppLocale
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.qalqon.QalqonCard
import uz.faceguard.app.data.prefs.AppLanguageStore

/** One line in the assistant conversation. Session-only; never persisted. */
data class AssistantMessage(val text: String, val fromUser: Boolean)

/**
 * The Qalqon Assistant screen state.
 *
 * Session-only conversation (no history is stored), backed by
 * [LocalQalqonKnowledgeAssistant]. It reads a *limited, non-sensitive* slice of the
 * existing runtime state (protection on/off, protected-app count, whether a child
 * exists, whether notifications are on) purely to answer "why…" questions; it never
 * reads or exposes the PIN, templates, embeddings or any credential.
 *
 * The assistant is entirely non-critical: every step is failure-isolated, so a failure
 * can never reach QALQON's protection.
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val languageStore: AppLanguageStore,
    private val runtime: ProtectionRuntime,
) : ViewModel() {

    private val strings: (Int) -> String = { res ->
        val language = languageStore.current()
        (language?.let { AppLocale.localizedContext(context, it).resources } ?: context.resources)
            .getString(res)
    }

    private val assistant: QalqonAssistant = LocalQalqonKnowledgeAssistant(HelpTextProvider(strings))

    private val _messages = MutableStateFlow(
        listOf(AssistantMessage(strings(R.string.help_assistant_greeting), fromUser = false)),
    )
    val messages: StateFlow<List<AssistantMessage>> = _messages

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking

    /** The suggested questions, resolved in the current language. */
    val suggestedQuestions: List<String> = SUGGESTED_QUESTIONS.map { strings(it) }

    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _thinking.value) return

        _messages.update { it + AssistantMessage(trimmed, fromUser = true) }
        _thinking.value = true
        viewModelScope.launch {
            val reply = runCatching {
                withContext(Dispatchers.Default) {
                    assistant.ask(trimmed, currentContext())
                }
            }.getOrNull()

            val text = when (reply) {
                null -> strings(R.string.help_assistant_unavailable)
                AssistantReply.OutOfDomain -> strings(R.string.help_assistant_ood)
                AssistantReply.NotFound -> strings(R.string.help_assistant_not_found)
                is AssistantReply.Contextual -> buildString {
                    append(strings(reply.bodyRes))
                    reply.followUpRes?.let { append("\n\n").append(strings(it)) }
                }
                is AssistantReply.Knowledge -> strings(reply.titleRes) + "\n\n" + strings(reply.bodyRes)
            }
            _messages.update { it + AssistantMessage(text, fromUser = false) }
            _thinking.value = false
        }
    }

    /** The non-sensitive runtime flags the assistant may reason about. */
    private fun currentContext(): QalqonAssistantContext {
        val state = runtime.state.value
        return QalqonAssistantContext(
            protectionEnabled = state.enabled,
            protectedAppsCount = state.protectedCount,
            hasChildren = state.childCount > 0,
            notificationsEnabled = state.notificationsEnabled,
        )
    }

    private companion object {
        val SUGGESTED_QUESTIONS = listOf(
            R.string.help_assistant_suggest_protection,
            R.string.help_assistant_suggest_add_child,
            R.string.help_assistant_suggest_apps,
            R.string.help_assistant_suggest_pin,
            R.string.help_assistant_suggest_face,
        )
    }
}

/**
 * The Qalqon Assistant: a dedicated conversation screen.
 *
 * The assistant is strictly domain-bound (see [QalqonAssistant]): it answers only
 * about QALQON from the local knowledge base, refuses unrelated questions with a
 * localized message, and never composes an answer of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpAssistantScreen(
    onBack: () -> Unit,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val thinking by viewModel.thinking.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val showSuggestions = messages.size == 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_assistant_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.request_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(QalqonDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
            ) {
                items(messages) { message -> MessageBubble(message) }

                if (showSuggestions) {
                    item {
                        Text(
                            stringResource(R.string.help_assistant_suggested_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(viewModel.suggestedQuestions) { suggestion ->
                        OutlinedButton(
                            onClick = { viewModel.ask(suggestion) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = suggestion,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (thinking) {
                    item {
                        Text(
                            stringResource(R.string.help_assistant_thinking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.help_assistant_input_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                IconButton(
                    onClick = {
                        viewModel.ask(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !thinking,
                    modifier = Modifier.width(QalqonDimens.sizes.touchTarget),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.help_assistant_send),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AssistantMessage) {
    QalqonCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (message.fromUser) {
                stringResource(R.string.help_assistant_you_prefix, message.text)
            } else {
                message.text
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (message.fromUser) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
