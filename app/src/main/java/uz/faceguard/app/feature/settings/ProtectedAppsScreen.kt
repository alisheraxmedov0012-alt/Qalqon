package uz.faceguard.app.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.faceguard.app.R
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.SectionCard
import uz.faceguard.app.domain.model.ProtectedApp

/**
 * The protected-apps catalogue (`settings_apps`), reached from the Protection
 * category.
 *
 * This is a presentation-only refinement: each row now shows the application's *real*
 * launcher icon (read from the same `PackageManager` metadata the catalogue is built
 * from) beside its name and the existing selection control. The catalogue data, the
 * selection callbacks and the child/global protection semantics are untouched — the
 * screen still drives exactly the existing [SettingsViewModel] toggles.
 *
 * The list is a [LazyColumn] keyed by `packageName` (the catalogue's stable identity),
 * so only visible rows resolve/render an icon, and the per-package icon lookup is
 * memoised off the main thread (see [AppIconResolver]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectedAppsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apps by viewModel.protectedApps.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.appsRefreshing.collectAsStateWithLifecycle()

    // Process-scoped resolver over the application context: no Activity is retained,
    // and one instance is shared by every row for the lifetime of the screen.
    val context = LocalContext.current.applicationContext
    val iconSizePx = with(LocalDensity.current) { QalqonDimens.sizes.avatar.roundToPx() }
    val iconResolver = remember(context, iconSizePx) { AppIconResolver(context, iconSizePx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.papps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            // Reuses the app's existing generic "Back" action label.
                            contentDescription = stringResource(R.string.request_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        ProtectedAppsContent(
            apps = apps,
            isRefreshing = isRefreshing,
            iconResolver = iconResolver,
            onToggle = viewModel::toggleProtectedApp,
            onRefresh = viewModel::refreshProtectedApps,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
private fun ProtectedAppsContent(
    apps: List<ProtectedApp>,
    isRefreshing: Boolean,
    iconResolver: AppIconResolver,
    onToggle: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val protectedCount = apps.count { it.isProtected }
    var query by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = QalqonDimens.screenPadding,
            end = QalqonDimens.screenPadding,
            top = QalqonDimens.spacing.lg,
            bottom = QalqonDimens.spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(QalqonDimens.spacing.sm),
    ) {
        item {
            Text(stringResource(R.string.papps_subtitle), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Text(
                stringResource(R.string.papps_selected_count, protectedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = if (protectedCount > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (isRefreshing) R.string.papps_refreshing else R.string.papps_refresh))
            }
        }

        if (apps.isEmpty()) {
            item {
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
            }
            return@LazyColumn
        }

        if (apps.size > SEARCH_THRESHOLD) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.papps_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Unchanged matching semantics: a case-insensitive substring of the app's own
        // label, which covers Latin, Cyrillic and the device's localized names.
        val visible = if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.appDisplayName.contains(query.trim(), ignoreCase = true) }
        }

        if (visible.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.papps_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(visible, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    iconResolver = iconResolver,
                    onToggle = onToggle,
                )
            }
        }
    }
}

/**
 * One catalogue row: real icon, name and the existing selection control.
 *
 * Both the row and the checkbox call the same existing `onToggle(packageName, …)`, so
 * the persisted selection behaviour is exactly what it was — this row is only its
 * presentation.
 */
@Composable
private fun AppRow(
    app: ProtectedApp,
    iconResolver: AppIconResolver,
    onToggle: (String, Boolean) -> Unit,
) {
    val stateLabel = stringResource(
        if (app.isProtected) R.string.papps_state_protected else R.string.papps_state_unprotected,
    )
    // Resolved up front (the semantics block below is not a composable scope).
    val rowDescription = stringResource(R.string.papps_row_description, app.appDisplayName, stateLabel)
    val icon: ImageBitmap? = rememberAppIcon(iconResolver, app.packageName)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = QalqonDimens.sizes.touchTarget)
            .clip(RoundedCornerShape(QalqonDimens.shapes.medium))
            .clickable(role = Role.Checkbox) { onToggle(app.packageName, !app.isProtected) }
            .semantics(mergeDescendants = true) {
                // A screen reader announces the app and whether it is protected; the
                // icon below is decorative and adds no noise.
                contentDescription = rowDescription
            }
            .padding(
                horizontal = QalqonDimens.spacing.sm,
                vertical = QalqonDimens.spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            icon = icon,
            highlighted = app.isProtected,
        )
        Text(
            text = app.appDisplayName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = QalqonDimens.spacing.md),
        )
        Checkbox(
            checked = app.isProtected,
            onCheckedChange = { onToggle(app.packageName, it) },
        )
    }
}

/**
 * The icon area: a subtle neutral container (tinted with the semantic palette while
 * the app is protected) holding the real launcher icon at a uniform size. When the
 * platform exposes no icon, the local generic glyph is shown instead — deterministic,
 * never a guessed identity.
 */
@Composable
private fun AppIcon(icon: ImageBitmap?, highlighted: Boolean) {
    val container = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .size(QalqonDimens.sizes.avatar)
            .clip(RoundedCornerShape(QalqonDimens.shapes.small))
            .background(color = container),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                // Decorative: the row's own description names the application.
                contentDescription = null,
                modifier = Modifier.size(QalqonDimens.sizes.avatar),
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(QalqonDimens.icon.sm),
            )
        }
    }
}

private const val SEARCH_THRESHOLD = 15
