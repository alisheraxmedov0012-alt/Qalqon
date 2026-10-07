package uz.faceguard.app.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.feature.home.DashboardStatus
import uz.faceguard.app.feature.home.DashboardUiState
import uz.faceguard.app.feature.home.HomeBannerKind
import uz.faceguard.app.feature.home.homeBanners

/**
 * Stage 8: camera-interruption feedback on the Home banner area.
 *
 * A camera recovery in progress is its own honest state — distinct from a post-reboot
 * camera limit and from a missing permission — and must appear without disturbing any
 * other banner condition.
 */
class Stage8CameraRecoveryBannerTest {

    private fun state(
        notificationsEnabled: Boolean = true,
        pendingRequests: Int = 0,
    ) = DashboardUiState(
        status = DashboardStatus.READY,
        protectionEnabled = true,
        notificationsEnabled = notificationsEnabled,
        pendingRequestCount = pendingRequests,
    )

    @Test
    fun aCameraRecoveryShowsItsOwnBanner() {
        val banners = homeBanners(state(), cameraRecovering = true)
        assertEquals(listOf(HomeBannerKind.CAMERA_RECOVERING), banners.map { it.kind })
    }

    @Test
    fun noCameraRecoveryMeansNoSuchBanner() {
        assertTrue(homeBanners(state()).none { it.kind == HomeBannerKind.CAMERA_RECOVERING })
        assertTrue(homeBanners(state(), cameraRecovering = false).isEmpty())
    }

    @Test
    fun theRecoveryBannerIsDistinctFromThePostRebootCameraLimit() {
        // Recovering is not the same condition as a post-reboot camera limit.
        val recovering = homeBanners(state(), cameraRecovering = true).map { it.kind }
        val bootLimited = homeBanners(state(), cameraLimitedAfterBoot = true).map { it.kind }
        assertEquals(listOf(HomeBannerKind.CAMERA_RECOVERING), recovering)
        assertEquals(listOf(HomeBannerKind.CAMERA_LIMITED), bootLimited)
    }

    @Test
    fun recoveryRanksBeforeThePostRebootLimit() {
        val kinds = homeBanners(
            state(),
            cameraLimitedAfterBoot = true,
            cameraRecovering = true,
        ).map { it.kind }
        assertTrue(
            "recovery should be listed before the boot limit",
            kinds.indexOf(HomeBannerKind.CAMERA_RECOVERING) < kinds.indexOf(HomeBannerKind.CAMERA_LIMITED),
        )
    }

    @Test
    fun degradedProtectionStillRanksBeforeCameraRecovery() {
        val kinds = homeBanners(
            state(),
            degradedCapabilities = setOf(uz.faceguard.app.domain.protection.ProtectionCapability.OVERLAY),
            cameraRecovering = true,
        ).map { it.kind }
        assertEquals(
            listOf(HomeBannerKind.PROTECTION_DEGRADED, HomeBannerKind.CAMERA_RECOVERING),
            kinds,
        )
    }

    @Test
    fun recoveryCoexistsWithOtherBannersWithinTheBannerCap() {
        val kinds = homeBanners(
            state(notificationsEnabled = false, pendingRequests = 2),
            cameraRecovering = true,
        ).map { it.kind }
        assertTrue(kinds.contains(HomeBannerKind.CAMERA_RECOVERING))
        assertFalse(kinds.size > 3)
    }
}
