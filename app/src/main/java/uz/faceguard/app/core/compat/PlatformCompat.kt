package uz.faceguard.app.core.compat

/**
 * Stage 7: the Android **version-compatibility matrix** as pure, testable decisions.
 *
 * QALQON runs on API 26 (Android 8.0) through API 36 (Android 16). Several platform
 * behaviours change across that range, and the exact gate for each is easy to get
 * subtly wrong — a wrong bitmask silently drops a foreground-service type, a missing
 * cutout mode leaves a strip of a "full-screen" block uncovered. Keeping every gate
 * here (instead of a scattered `Build.VERSION.SDK_INT` comparison) means the whole
 * matrix is deterministic and asserted on the JVM for **every** supported API level.
 *
 * This file is deliberately Android-free: it takes an `sdkInt` and returns a value.
 * The Android call sites pass `Build.VERSION.SDK_INT`, which is also what keeps the
 * `NewApi` lint analysis able to see the guarding checks at those call sites.
 *
 * Boundaries that matter (verified against the platform SDK):
 *  - API 28 (P): display cutouts + `layoutInDisplayCutoutMode`.
 *  - API 29 (Q): `unsafeCheckOpNoThrow` for the usage app-op.
 *  - API 30 (R): the `camera` foreground-service type.
 *  - API 33 (T): `POST_NOTIFICATIONS` becomes a runtime permission.
 *  - API 34 (U): the `specialUse` foreground-service type; runtime-registered
 *    receivers must declare their export behaviour.
 */
object PlatformCompat {

    // ---------------------------------------------------------------- API levels

    const val API_CUTOUT = 28
    const val API_UNSAFE_CHECK_OP = 29
    const val API_CAMERA_FGS_TYPE = 30
    const val API_NOTIFICATION_PERMISSION = 33
    const val API_SPECIAL_USE_FGS_TYPE = 34

    /**
     * The first API level at which `POST_NOTIFICATIONS` is a runtime permission.
     * Single source of truth for the permission-request gate (the requests screen
     * re-exports this as its own constant).
     */
    const val NOTIFICATION_PERMISSION_API = API_NOTIFICATION_PERMISSION

    // ---------------------------------------------------- foreground service types

    /**
     * Foreground-service type bits, mirroring `android.content.pm.ServiceInfo`.
     * Repeated here as constants so the whole matrix is JVM testable; a drift guard
     * asserts equality with the real platform constants on a device.
     */
    const val FGS_TYPE_NONE = 0
    const val FGS_TYPE_CAMERA = 0x40 // ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA (API 30)
    const val FGS_TYPE_SPECIAL_USE = 0x40000000 // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE (API 34)

    /**
     * The runtime foreground-service type bitmask to pass to `startForeground`.
     *
     * `specialUse` is only a real type from API 34 and the `camera` type from API 30;
     * below those a bit the platform does not define must never be sent, so it is
     * omitted. The base (no camera) mask is `specialUse` on API 34+ and `NONE` below —
     * exactly the pre-Stage-7 behaviour, now asserted for every level.
     */
    fun foregroundServiceTypes(sdkInt: Int, includeCamera: Boolean): Int {
        var types = FGS_TYPE_NONE
        if (sdkInt >= API_SPECIAL_USE_FGS_TYPE) types = types or FGS_TYPE_SPECIAL_USE
        if (includeCamera && sdkInt >= API_CAMERA_FGS_TYPE) types = types or FGS_TYPE_CAMERA
        return types
    }

    /** True when the platform defines the `camera` foreground-service type. */
    fun supportsCameraForegroundType(sdkInt: Int): Boolean = sdkInt >= API_CAMERA_FGS_TYPE

    /** True when the platform defines the `specialUse` foreground-service type. */
    fun supportsSpecialUseForegroundType(sdkInt: Int): Boolean = sdkInt >= API_SPECIAL_USE_FGS_TYPE

    // ------------------------------------------------------------- display cutout

    /**
     * Display-cutout layout modes, mirroring
     * `android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_*`.
     */
    const val CUTOUT_MODE_DEFAULT = 0
    const val CUTOUT_MODE_SHORT_EDGES = 1
    const val CUTOUT_MODE_NEVER = 2
    const val CUTOUT_MODE_ALWAYS = 3

    /**
     * The cutout mode a **full-screen blocking overlay** must use so it also covers
     * the notch/cutout region instead of leaving a strip where the protected app
     * shows through.
     *
     * `ALWAYS` (API 30+) is the strictest; `SHORT_EDGES` (API 28+) covers the common
     * top/bottom notch; below API 28 there is no cutout concept, so the default is
     * correct. Never returns `NEVER`.
     */
    fun fullscreenOverlayCutoutMode(sdkInt: Int): Int = when {
        sdkInt >= 30 -> CUTOUT_MODE_ALWAYS
        sdkInt >= API_CUTOUT -> CUTOUT_MODE_SHORT_EDGES
        else -> CUTOUT_MODE_DEFAULT
    }

    /** True when `WindowManager.LayoutParams.layoutInDisplayCutoutMode` exists. */
    fun supportsDisplayCutoutMode(sdkInt: Int): Boolean = sdkInt >= API_CUTOUT

    // ------------------------------------------------------------- notifications

    /** True when `POST_NOTIFICATIONS` must be requested at runtime (API 33+). */
    fun notificationRuntimePermissionRequired(sdkInt: Int): Boolean =
        sdkInt >= NOTIFICATION_PERMISSION_API

    // ------------------------------------------------------------------ usage

    /**
     * True when `AppOpsManager.unsafeCheckOpNoThrow` (the non-deprecated app-op read)
     * is available; below this the int-op overload is used.
     */
    fun supportsUnsafeCheckOp(sdkInt: Int): Boolean = sdkInt >= API_UNSAFE_CHECK_OP
}
