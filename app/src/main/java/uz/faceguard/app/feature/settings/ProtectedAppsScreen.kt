package uz.faceguard.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.faceguard.app.R
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.SectionCard
import uz.faceguard.app.domain.model.ProtectedApp

/**
 * UI/UX redesign, Phase 6: the protected-apps catalogue.
 *
 * This is the *account-wide* catalogue the protection runtime already enforces — the
 * same destination the app has always had (`settings_apps`), now reached from the
 * Protection category. Its content and the existing [SettingsViewModel] toggles are
 * unchanged; only the surrounding chrome is the new category page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectedAppsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apps by viewModel.protectedApps.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.appsRefreshing.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.papps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(QalqonDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.md),
        ) {
            ProtectedAppsContent(
                apps = apps,
                isRefreshing = isRefreshing,
                onToggle = viewModel::toggleProtectedApp,
                onRefresh = viewModel::refreshProtectedApps,
            )
        }
    }
}

@Composable
private fun ProtectedAppsContent(
    apps: List<ProtectedApp>,
    isRefreshing: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val protectedCount = apps.count { it.isProtected }
    var query by remember { mutableStateOf("") }

    Text(stringResource(R.string.papps_subtitle), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.papps_selected_count, protectedCount),
        style = MaterialTheme.typography.bodyMedium,
        color = if (protectedCount > 0) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedButton(
        onClick = onRefresh,
        enabled = !isRefreshing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (isRefreshing) R.string.papps_refreshing else R.string.papps_refresh))
    }

    if (apps.isEmpty()) {
        SectionCard(title = stringResource(R.string.papps_title)) {
            Text(
                stringResource(if (isRefreshing) R.string.papps_loading else R.string.papps_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!isRefreshing) {
                Text(
                    stringResource(R.string.papps_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    SectionCard(title = stringResource(R.string.papps_title)) {
        if (apps.size > SEARCH_THRESHOLD) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.papps_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val visible = if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.appDisplayName.contains(query.trim(), ignoreCase = true) }
        }
        if (visible.isEmpty()) {
            Text(
                stringResource(R.string.papps_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            visible.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(app.packageName, !app.isProtected) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = app.isProtected,
                        onCheckedChange = { onToggle(app.packageName, it) },
                    )
                    Text(
                        app.appDisplayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (app.isProtected) {
                        Text(
                            stringResource(R.string.papps_marked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private const val SEARCH_THRESHOLD = 15
