package uz.faceguard.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp


/**
 * Offline-first foundation: no network clients are created anywhere in this app.
 * All state lives in Room + DataStore. Face/protection features land in later phases.
 */
@HiltAndroidApp
class FaceGuardApp : Application()
