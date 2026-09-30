package uz.faceguard.app.feature.language

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import uz.faceguard.app.R
import uz.faceguard.app.core.i18n.AppLanguage
import uz.faceguard.app.core.i18n.LanguageState
import uz.faceguard.app.data.prefs.AppLanguageStore

@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val store: AppLanguageStore,
) : ViewModel() {

    val state = store.state

    fun select(language: AppLanguage) {
        viewModelScope.launch { store.setLanguage(language) }
    }
}

/**
 * First-launch language selection.
 *
 * Shown before registration/login whenever no language has been chosen. Each
 * option is labelled with its own endonym, so the screen is readable without
 * relying on the currently active language, and there is no preselected answer —
 * a fresh install never silently adopts a language it was not told to use.
 *
 * Selecting persists through the single [AppLanguageStore] and the app applies it
 * immediately (the surrounding `LocalizedApp` re-renders the whole tree); no
 * restart is required.
 */
@Composable
fun LanguageScreen(
    onDone: () -> Unit,
    viewModel: LanguageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected = (state as? LanguageState.Selected)?.language

    Scaffold { padding ->
        FirstLaunchLanguageContent(
            selected = selected,
            onSelect = { language ->
                viewModel.select(language)
                onDone()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        )
    }
}

/** The language list, shared by the first-launch screen and text-only previews. */
@Composable
fun FirstLaunchLanguageContent(
    selected: AppLanguage?,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.language_select_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.language_select_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        LanguageOptions(
            selected = selected,
            onSelect = onSelect,
        )
    }
}

/**
 * The three selectable languages, labelled with their own endonyms.
 *
 * Shared with Settings so changing the language later goes through exactly the
 * same options and the same persisted value — there is no second language
 * preference to drift out of sync.
 */
@Composable
fun LanguageOptions(
    selected: AppLanguage?,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppLanguage.entries.forEach { language ->
            LanguageRow(
                language = language,
                selected = language == selected,
                onSelect = { onSelect(language) },
            )
        }
    }
}

@Composable
private fun LanguageRow(
    language: AppLanguage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.fillMaxWidth(0.04f))
            Text(
                stringResource(language.nativeNameRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    if (selected) {
        Card(modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) { content() }
    }
}
