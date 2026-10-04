package uz.faceguard.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.feature.home.ChildSection
import uz.faceguard.app.feature.home.DashboardStatus
import uz.faceguard.app.feature.home.DashboardUiState
import uz.faceguard.app.feature.home.HomeBannerKind
import uz.faceguard.app.feature.home.HomeHeroSurface
import uz.faceguard.app.feature.home.HomeProtectionStatus
import uz.faceguard.app.feature.home.HomeSetupStepKind
import uz.faceguard.app.feature.home.PolicyCounts
import uz.faceguard.app.feature.home.ScreenTimeSummaryStatus
import uz.faceguard.app.feature.home.ScreenTimeSummaryUiState
import uz.faceguard.app.feature.home.homeBanners
import uz.faceguard.app.feature.home.homeSetupComplete
import uz.faceguard.app.feature.home.homeSetupSteps
import uz.faceguard.app.feature.home.homeCanEnableProtection
import uz.faceguard.app.feature.home.homeProtectionHeroSurface
import uz.faceguard.app.feature.home.homeProtectionStatus
import uz.faceguard.app.feature.home.homeTodayMetrics

/**
 * The Home state -> UI mapping, Compose-free.
 *
 * Covers the four situations the design must handle: (1) no child profile, (2) a child
 * with protection OFF, (3) protection ON, (4) degraded protection. This is the layer
 * that decides what the screen shows, so it is pinned here rather than in a UI test.
 */
class HomeStateMappingTest {

    private fun childProfile(id: Long = 1L, name: String = "Ali") = ChildProfile(
        id = id,
        accountId = 1L,
        childName = name,
        isFaceEnrolled = true,
        restrictionLevel = RestrictionLevel.MEDIUM,
    )

    private fun childSection(id: Long = 1L) = ChildSection(
        childId = id,
        name = "Ali",
        faceEnrolled = true,
        level = RestrictionLevel.MEDIUM,
        policies = PolicyCounts(),
        limits = emptyList(),
        policiesLoading = false,
    )

    private fun state(
        protectionEnabled: Boolean = false,
        protectionState: ProtectionState = ProtectionState.UNPROTECTED,
        children: List<ChildProfile> = emptyList(),
        child: ChildSection? = null,
        overlay: Boolean = false,
        usage: Boolean = false,
        accessibility: Boolean = false,
        notificationsEnabled: Boolean = true,
    ) = DashboardUiState(
        status = DashboardStatus.READY,
        protectionEnabled = protectionEnabled,
        protectionState = protectionState,
        children = children,
        selectedChildId = child?.childId,
        child = child,
        overlayGranted = overlay,
        usageAccessGranted = usage,
        accessibilityEnabled = accessibility,
        notificationsEnabled = notificationsEnabled,
    )

    private fun summary(status: ScreenTimeSummaryStatus = ScreenTimeSummaryStatus.NO_TARGET) =
        ScreenTimeSummaryUiState(status = status)

    // ------------------------------------------------ 1. no child profile

    @Test
    fun noChild_heroIsWarning_todayHasNoChildScopedMetrics_andProtectionIsDisabled() {
        val s = state()
        assertFalse("protection cannot be enabled without a child", homeCanEnableProtection(s))
        assertEquals(HomeHeroSurface.WARNING, homeProtectionHeroSurface(homeProtectionStatus(s)))
        // No child => no schedule / eye-safety metrics (screen time may still show its own state).
        val metrics = homeTodayMetrics(s, summary())
        assertTrue(metrics.all { it.kind.name != "SCHEDULE" })
        assertTrue(metrics.all { it.kind.name != "EYE_SAFETY" })
    }

    // ------------------------------------------------ 2. child + protection OFF

    @Test
    fun childWithProtectionOff_heroIsWarning_andProtectionCanBeEnabled() {
        val s = state(
            protectionEnabled = false,
            children = listOf(childProfile()),
            child = childSection(),
        )
        assertTrue(homeCanEnableProtection(s))
        assertEquals(HomeProtectionStatus.OFF, homeProtectionStatus(s))
        assertEquals(HomeHeroSurface.WARNING, homeProtectionHeroSurface(homeProtectionStatus(s)))
    }

    // ------------------------------------------------ 3. protection ON

    @Test
    fun protectionOn_heroIsGreen() {
        val s = state(
            protectionEnabled = true,
            children = listOf(childProfile()),
            child = childSection(),
            overlay = true,
            usage = true,
            accessibility = true,

        )
        assertEquals(HomeProtectionStatus.ACTIVE, homeProtectionStatus(s))
        assertEquals(HomeHeroSurface.SUCCESS, homeProtectionHeroSurface(homeProtectionStatus(s)))
        assertTrue(homeBanners(s).isEmpty())
    }

    // ------------------------------------------------ 4. degraded protection

    @Test
    fun degradedProtection_surfacesTheMostSevereBannerFirst() {
        val s = state(
            protectionEnabled = true,
            children = listOf(childProfile()),
            child = childSection(),
            overlay = true,
            usage = true,
            accessibility = false,

            notificationsEnabled = false,
        )
        val banners = homeBanners(
            state = s,
            degradedCapabilities = setOf(
                uz.faceguard.app.domain.protection.ProtectionCapability.ACCESSIBILITY,
                uz.faceguard.app.domain.protection.ProtectionCapability.CAMERA,
            ),
        )
        assertEquals(HomeBannerKind.PROTECTION_DEGRADED, banners.first().kind)
        assertTrue(banners.any { it.kind == HomeBannerKind.NOTIFICATIONS_DISABLED })
    }

    @Test
    fun anOffDeviceIsNotReportedAsDegraded() {
        // A device with protection switched off is not "degraded" (nothing to nag about):
        // degradation is derived from the runtime state, which reports nothing when off.
        val s = state(protectionEnabled = false)
        assertTrue(homeBanners(s).isEmpty())
    }

    // ------------------------------------------------ 5. setup checklist

    @Test
    fun theSetupChecklistReflectsRealProgressInOrder() {
        val fresh = homeSetupSteps(state())
        assertEquals(
            listOf(
                HomeSetupStepKind.CHILD_ADDED,
                HomeSetupStepKind.PERMISSIONS_GRANTED,
                HomeSetupStepKind.PROTECTION_ON,
            ),
            fresh.map { it.kind },
        )
        assertTrue("nothing is done on a fresh account", fresh.none { it.done })
        assertFalse(homeSetupComplete(state()))

        val partly = homeSetupSteps(state(children = listOf(childProfile())))
        assertTrue("the child step is done", partly.first().done)
        assertFalse("permissions are still open", partly[1].done)
        assertFalse(homeSetupComplete(state(children = listOf(childProfile()))))

        val done = state(
            protectionEnabled = true,
            children = listOf(childProfile()),
            child = childSection(),
            overlay = true,
            usage = true,
            accessibility = true,
        )
        assertTrue(homeSetupComplete(done))
        assertTrue(homeSetupSteps(done).all { it.done })
    }
}
