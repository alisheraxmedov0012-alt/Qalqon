package uz.faceguard.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.protection.ProtectionBootReceiver
import uz.faceguard.app.core.protection.ProtectionForegroundService

/**
 * Stage 5: structural guards for the background-runtime restart contract.
 *
 * The pure decision is unit-tested (`ProtectionServiceLifecyclePolicyTest`); this
 * asserts the merged manifest actually matches it, so a build-time change (a stray
 * `stopWithTask="true"`, a dropped boot receiver, a lost FGS type) cannot silently
 * reintroduce the failure. Runs in CI on the emulator; **not** executed in this
 * environment (no KVM/emulator) — it is not device validation.
 */
@RunWith(AndroidJUnit4::class)
class ProtectionServiceRestartPolicyInstrumentedTest {

    private val appContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun services() = appContext.packageManager
        .getPackageInfo(appContext.packageName, PackageManager.GET_SERVICES)
        .services
        ?.toList()
        .orEmpty()

    private fun service() = services()
        .firstOrNull { it.name == ProtectionForegroundService::class.java.name }

    @Test
    fun protectionService_mustNotStopWithTheTask() {
        val info = service()
        assertNotNull("the protection foreground service must be declared", info)
        assertEquals(
            "Recents removal must not end protection (a child could swipe the app away)",
            0,
            info!!.flags and ServiceInfo.FLAG_STOP_WITH_TASK,
        )
    }

    @Test
    fun protectionService_keepsTheCameraAndSpecialUseTypes() {
        val info = service()
        assertNotNull(info)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            info!!.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    @Test
    fun bootReceiver_remainsDeclaredForBootCompleted() {
        val receivers = appContext.packageManager
            .getPackageInfo(appContext.packageName, PackageManager.GET_RECEIVERS)
            .receivers
            ?.toList()
            .orEmpty()

        val receiver = receivers.firstOrNull { it.name == ProtectionBootReceiver::class.java.name }
        assertNotNull("protection must still be restorable after a reboot", receiver)
        assertTrue(
            "the boot receiver must be enabled and exported (system-only broadcast)",
            receiver!!.enabled && receiver.exported,
        )

        val bootIntent = Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(appContext.packageName)
        val resolved = appContext.packageManager
            .queryBroadcastReceivers(bootIntent, 0)
            .mapNotNull { it.activityInfo?.name }
        assertTrue(
            "the boot receiver must resolve for BOOT_COMPLETED (was: $resolved)",
            resolved.contains(ProtectionBootReceiver::class.java.name),
        )
    }
}
