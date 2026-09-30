package uz.faceguard.app.core.time

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.domain.screentime.ElapsedTimeSource

/**
 * The production [ElapsedTimeSource]: `SystemClock.elapsedRealtime()`.
 *
 * That clock counts milliseconds since boot, keeps running while the device sleeps,
 * and cannot be moved by the user or the network — exactly what measuring a usage
 * duration needs. Tests inject their own fake source instead.
 */
@Singleton
class SystemElapsedTimeSource @Inject constructor() : ElapsedTimeSource {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
