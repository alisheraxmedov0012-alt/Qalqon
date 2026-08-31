package uz.faceguard.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import uz.faceguard.app.R

/**
 * Onboarding explains the core promises up front: parent vs child faces,
 * fully offline operation, on-device processing, and battery-friendly scans.
 */
@Composable
fun WelcomeScreen(
    onRegister: () -> Unit,
    onLogin: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.welcome_subtitle), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            listOf(
                R.string.welcome_point_faces,
                R.string.welcome_point_offline,
                R.string.welcome_point_on_device,
                R.string.welcome_point_battery,
            ).forEach { res ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRegister, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.welcome_register))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.welcome_login))
            }
        }
    }
}
