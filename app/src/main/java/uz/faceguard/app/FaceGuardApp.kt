package uz.faceguard.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import uz.faceguard.app.core.protection.ProtectionRuntime


/**
 * Offline-first foundation: no network clients are created anywhere in this app.
 * All state lives in Room + DataStore. The app-scoped protection session starts
 * here and is activated by the persisted `protectionEnabled` setting.
 */
@HiltAndroidApp
class FaceGuardApp : Application() {

    @Inject
    lateinit var protectionRuntime: ProtectionRuntime

    override fun onCreate() {
        super.onCreate()
        protectionRuntime.start()
    }
}
