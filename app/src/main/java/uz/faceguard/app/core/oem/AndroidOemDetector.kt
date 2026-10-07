package uz.faceguard.app.core.oem

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.domain.oem.OemDetector
import uz.faceguard.app.domain.oem.OemFamily

/**
 * Stage 6: the single Android reader of the device's OEM identity.
 *
 * `Build.MANUFACTURER`/`BRAND`/`MODEL` are constants for the process lifetime, so
 * the family is resolved **once** and cached — the compatibility layer adds no
 * per-frame or per-probe work. The decision itself is delegated to the pure
 * [OemDetector], so detection is identical on every device and JVM-testable.
 *
 * Detection is local-only: nothing about the device is stored or sent anywhere.
 */
@Singleton
class AndroidOemDetector @Inject constructor() {
    /** The device's family, resolved once from the standard build strings. */
    val family: OemFamily by lazy {
        OemDetector.detect(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
        )
    }

    /**
     * The raw identity strings, for developer diagnostics only. Never persisted and
     * never transmitted; the app has no INTERNET permission.
     */
    fun identity(): DeviceIdentity = DeviceIdentity(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        model = Build.MODEL.orEmpty(),
    )
}

/** Raw build strings, exposed read-only for the debug diagnostics screen. */
data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
)
