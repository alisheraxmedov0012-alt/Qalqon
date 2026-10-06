package uz.faceguard.app.domain.oem

/**
 * Stage 6: the compatibility profiles for every recognised family.
 *
 * IMPORTANT — these component names are **candidates**, not guarantees. OEM
 * settings activities are undocumented, renamed across OS versions and removed on
 * some models. The Android layer (`core.oem.AndroidOemSettings`) resolves each
 * candidate before launching it and falls back to the generic platform pages, so a
 * stale candidate degrades to "open the app's settings" instead of a crash. No
 * hidden API, root or reflection is used — only public `ComponentName` intents.
 */
object OemCompatibilityProfiles {

    fun forFamily(family: OemFamily): OemCompatibilityProfile = when (family) {
        OemFamily.XIAOMI, OemFamily.REDMI, OemFamily.POCO -> OemCompatibilityProfile(
            family = family,
            supportLevel = OemSupportLevel.SUPPORTED,
            autostartCandidates = listOf(
                ComponentSpec(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            // HyperOS/MIUI battery management is exposed as the platform battery page;
            // the OEM app-detail page is the reliable app-specific surface.
        )

        OemFamily.OPPO, OemFamily.REALME, OemFamily.ONEPLUS -> OemCompatibilityProfile(
            family = family,
            supportLevel = OemSupportLevel.SUPPORTED,
            autostartCandidates = listOf(
                ComponentSpec(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                ComponentSpec(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.startupapp.StartupAppListActivity",
                ),
                ComponentSpec(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                ),
            ),
        )

        OemFamily.VIVO -> OemCompatibilityProfile(
            family = family,
            supportLevel = OemSupportLevel.SUPPORTED,
            autostartCandidates = listOf(
                ComponentSpec(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                ComponentSpec(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
            ),
        )

        OemFamily.HONOR, OemFamily.HUAWEI -> OemCompatibilityProfile(
            family = family,
            supportLevel = OemSupportLevel.SUPPORTED,
            autostartCandidates = listOf(
                ComponentSpec(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                ComponentSpec(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            ),
        )

        // Samsung has no per-app autostart page; "Device care" battery settings is the
        // relevant surface. No autostart candidate on purpose — guidance points at
        // battery optimization instead of a page that does not exist.
        OemFamily.SAMSUNG -> OemCompatibilityProfile(
            family = family,
            supportLevel = OemSupportLevel.GENERIC,
        )

        // Google/Motorola/unknown ship standard Android background behaviour.
        OemFamily.GOOGLE,
        OemFamily.MOTOROLA,
        OemFamily.UNKNOWN,
        -> OemCompatibilityProfile(family = family, supportLevel = OemSupportLevel.GENERIC)
    }
}
