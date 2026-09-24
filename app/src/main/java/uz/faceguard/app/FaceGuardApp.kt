package uz.faceguard.app

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.data.repository.BiometricMigrationService


/**
 * Offline-first foundation: no network clients are created anywhere in this app.
 * All state lives in Room + DataStore. The app-scoped protection session starts
 * here and is activated by the persisted `protectionEnabled` setting.
 */
@HiltAndroidApp
class FaceGuardApp : Application(), CameraXConfig.Provider {

    @Inject
    lateinit var protectionRuntime: ProtectionRuntime

    @Inject
    lateinit var biometricMigration: BiometricMigrationService

    /** App-scoped, IO confined: the encryption sweep must never block startup. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * CameraX requires a configured [CameraXConfig.Provider]; without one,
     * `ProcessCameraProvider` throws "CameraX is not configured properly".
     * [Camera2Config] comes from the `camera-camera2` dependency that backs the
     * front-camera capture, and declaring it here makes the configuration
     * explicit instead of relying on manifest meta-data merging.
     */
    override fun getCameraXConfig(): CameraXConfig = Camera2Config.defaultConfig()

    override fun onCreate() {
        super.onCreate()
        protectionRuntime.start()
        // Phase 12: encrypt any pre-existing plaintext biometric rows. Idempotent,
        // off the main thread, and a failure never blocks the app (the security state
        // reports RECOVERY_REQUIRED instead).
        appScope.launch {
            runCatching { biometricMigration.migrateLegacyTemplates() }
        }
    }
}
