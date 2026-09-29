package uz.faceguard.app.core

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.accessibility.ProtectionAccessibilityService
import uz.faceguard.app.core.protection.ProtectionForegroundService

/**
 * Phase 7.1: the background-camera capability boundary.
 *
 * QALQON now runs a process-scoped camera session owned by the protection
 * foreground service, so the service must declare the `camera` foreground type
 * and the app must hold FOREGROUND_SERVICE_CAMERA. The camera type is claimed at
 * runtime only in the legal while-in-use foreground moment (see
 * ProtectionForegroundService), which these structural guards cannot assert; what
 * they protect is that the declaration never drifts:
 *  - the service also keeps `specialUse` (it is a parental-control keep-alive with
 *    a special-use subtype), and
 *  - the accessibility layer still never touches the camera.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundCameraBoundaryTest {

    private val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val packageManager get() = appContext.packageManager

    private fun services() = packageManager
        .getPackageInfo(appContext.packageName, PackageManager.GET_SERVICES)
        .services
        ?.toList()
        .orEmpty()

    @Test
    fun protectionForegroundService_claimsCameraAndSpecialUse() {
        val service = services().firstOrNull { it.name == ProtectionForegroundService::class.java.name }

        assertNotNull("the protection foreground service must be declared", service)
        assertEquals(
            "the process-scoped camera session requires the camera type",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            service!!.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        assertEquals(
            "the service is also a parental-control keep-alive, so specialUse stays",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    @Test
    fun accessibilityService_doesNotClaimTheCameraType() {
        val service = services().firstOrNull { it.name == ProtectionAccessibilityService::class.java.name }

        assertNotNull("the accessibility service must be declared", service)
        assertEquals(
            "the accessibility layer must not run the camera",
            0,
            service!!.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
    }

    @Test
    fun cameraForegroundService_permissionsAreRequested() {
        val requested = packageManager
            .getPackageInfo(appContext.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()

        assertTrue("foreground capture needs the camera permission", "android.permission.CAMERA" in requested)
        assertTrue(
            "a camera foreground service requires FOREGROUND_SERVICE_CAMERA on Android 14+",
            "android.permission.FOREGROUND_SERVICE_CAMERA" in requested,
        )
    }
}
