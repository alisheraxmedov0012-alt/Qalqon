package uz.faceguard.app.core.ui.qalqon

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.faceguard.app.R
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.ui.protectionCapabilityLabelRes
import uz.faceguard.app.domain.protection.PROTECTION_CAPABILITY_PRIORITY
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.domain.protection.highestPriorityMissing

/**
 * Persistent, prominent warning shown while protection is only partially effective.
 *
 * Built on the shared [QalqonStatusBanner] so it looks like the rest of the product,
 * and driven entirely by string resources (nothing is hardcoded). The body names
 * every missing capability; the single action jumps to the settings page of the most
 * consequential one ([highestPriorityMissing]), because that is the fix that restores
 * the most enforcement with one tap.
 *
 * Renders nothing when [missing] is empty, so a caller can place it unconditionally.
 */
@Composable
fun ProtectionDegradedBanner(
    missing: Set<ProtectionCapability>,
    onFix: (ProtectionCapability) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (missing.isEmpty()) return

    val names = PROTECTION_CAPABILITY_PRIORITY
        .filter { it in missing }
        .map { stringResource(protectionCapabilityLabelRes(it)) }
        .joinToString(", ")

    QalqonStatusBanner(
        text = stringResource(R.string.protection_degraded_body, names),
        severity = QalqonAlertSeverity.WARNING,
        modifier = modifier,
        title = stringResource(R.string.protection_degraded_title),
        action = {
            missing.highestPriorityMissing()?.let { capability ->
                Button(onClick = { onFix(capability) }) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(QalqonDimens.icon.sm),
                    )
                    Spacer(Modifier.size(QalqonDimens.spacing.sm))
                    Text(stringResource(R.string.protection_degraded_action))
                }
            }
        },
    )
}
