package uz.faceguard.app.home

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.ui.qalqon.QalqonStatusTone
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleResolution
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow
import uz.faceguard.app.feature.home.ChildSection
import uz.faceguard.app.feature.home.DashboardStatus
import uz.faceguard.app.feature.home.DashboardUiState
import uz.faceguard.app.feature.home.EyeSafetySection
import uz.faceguard.app.feature.home.HOME_BANNER_MAX
import uz.faceguard.app.feature.home.HOME_QUICK_ACTIONS_MAX
import uz.faceguard.app.feature.home.HomeBannerKind
import uz.faceguard.app.feature.home.HomeHeroSurface
import uz.faceguard.app.feature.home.HomeMetricValue
import uz.faceguard.app.feature.home.HomeProtectionStatus
import uz.faceguard.app.feature.home.HomeQuickAction
import uz.faceguard.app.feature.home.HomeTodayMetricKind
import uz.faceguard.app.feature.home.PolicyCounts
import uz.faceguard.app.feature.home.ScreenTimeInfoLabel
import uz.faceguard.app.feature.home.ScreenTimeInfoRow
import uz.faceguard.app.feature.home.ScreenTimeSummaryStatus
import uz.faceguard.app.R
import uz.faceguard.app.feature.home.ScreenTimeSummaryUiState
import uz.faceguard.app.domain.protection.ProtectionCapability
import uz.faceguard.app.feature.home.homeBanners
import uz.faceguard.app.feature.home.homeChildSummaries
import uz.faceguard.app.feature.home.homeProtectionLabelRes
import uz.faceguard.app.feature.home.homeProtectionHeroSurface
import uz.faceguard.app.feature.home.homeProtectionStatus
import uz.faceguard.app.feature.home.homeProtectionSupportingRes
import uz.faceguard.app.feature.home.homeProtectionTone
import uz.faceguard.app.feature.home.homeQuickActionLabelRes
import uz.faceguard.app.feature.home.homeQuickActions
import uz.faceguard.app.feature.home.homeTodayMetrics

/**
 * UI/UX redesign Phase 3: the Home dashboard presentation mapping.
 *
 * Pure JVM — every branch the dashboard can render is exercised from the existing
 * data shapes, so the information architecture is pinned without a device.
 */
class HomeDashboardPresentationTest {

    // ------------------------------------------------------------ protection

    @Test
    fun activeProtectionIsReportedWhenEnabledAndEveryCapabilityIsGranted() {
        val status = homeProtectionStatus(
            readyState(protectionEnabled = true, overlay = true, usage = true, accessibility = true),
        )
        assertEquals(HomeProtectionStatus.ACTIVE, status)
        assertEquals(QalqonStatusTone.ACTIVE, homeProtectionTone(status))
    }

    @Test
    fun inactiveProtectionIsReportedWhenTheSettingIsOff() {
        val status = homeProtectionStatus(readyState(protectionEnabled = false))
        assertEquals(HomeProtectionStatus.OFF, status)
        // OFF is a warning state (amber), not neutral: an unprotected device needs action.
        assertEquals(QalqonStatusTone.WARNING, homeProtectionTone(status))
        assertEquals(HomeHeroSurface.WARNING, homeProtectionHeroSurface(status))
    }

    @Test
    fun aHardBlockOutranksEverythingElse() {
        val status = homeProtectionStatus(
            readyState(
                protectionEnabled = true,
                protectionState = ProtectionState.HARD_BLOCKED,
                overlay = true, usage = true, accessibility = true,
            ),
        )
        assertEquals(HomeProtectionStatus.BLOCKING, status)
        assertEquals(QalqonStatusTone.BLOCKING, homeProtectionTone(status))
    }

    @Test
    fun aSoftBlockIsAlsoReportedAsBlocking() {
        assertEquals(
            HomeProtectionStatus.BLOCKING,
            homeProtectionStatus(readyState(protectionEnabled = true, protectionState = ProtectionState.SOFT_BLOCKED)),
        )
    }

    @Test
    fun recoveringIsItsOwnStateAndWarns() {
        val status = homeProtectionStatus(
            readyState(protectionEnabled = true, protectionState = ProtectionState.RECOVERING),
        )
        assertEquals(HomeProtectionStatus.RECOVERING, status)
        assertEquals(QalqonStatusTone.WARNING, homeProtectionTone(status))
    }

    @Test
    fun enabledButMissingACapabilityIsSetupRequiredNotActive() {
        val status = homeProtectionStatus(
            readyState(protectionEnabled = true, overlay = true, usage = true, accessibility = false),
        )
        assertEquals(HomeProtectionStatus.SETUP_REQUIRED, status)
        assertEquals(QalqonStatusTone.WARNING, homeProtectionTone(status))
    }

    @Test
    fun everyProtectionStatusHasALabelAndASupportingLine() {
        HomeProtectionStatus.entries.forEach { status ->
            assertTrue("${status.name} label", homeProtectionLabelRes(status) != 0)
            assertTrue("${status.name} supporting", homeProtectionSupportingRes(status) != 0)
        }
    }

    // --------------------------------------------------------------- banners

    @Test
    fun aCalmDashboardHasNoBanners() {
        assertTrue(homeBanners(readyState()).isEmpty())
    }

    @Test
    fun pendingRequestsSurfaceWithTheirRealCount() {
        val banners = homeBanners(readyState(pendingRequests = 3))
        assertEquals(1, banners.size)
        assertEquals(HomeBannerKind.PENDING_REQUESTS, banners.first().kind)
        assertEquals(3, banners.first().count)
    }

    @Test
    fun aChildWithoutAFaceSurfacesAsSetupNeeded() {
        val banners = homeBanners(
            readyState(children = listOf(childProfile(1), childProfile(2, enrolled = false))),
        )
        assertEquals(HomeBannerKind.CHILDREN_NEED_SETUP, banners.single().kind)
        assertNull("setup-needed is not a counted condition", banners.single().count)
    }

    @Test
    fun disabledNotificationsSurface() {
        val banners = homeBanners(readyState(notificationsEnabled = false))
        assertEquals(HomeBannerKind.NOTIFICATIONS_DISABLED, banners.single().kind)
    }

    @Test
    fun degradedProtectionIsTheMostSevereBanner() {
        val banners = homeBanners(
            state = readyState(pendingRequests = 1),
            degradedCapabilities = setOf(ProtectionCapability.ACCESSIBILITY),
        )
        assertEquals(HomeBannerKind.PROTECTION_DEGRADED, banners.first().kind)
    }

    @Test
    fun theBannerAreaIsOneListAndBounded() {
        val banners = homeBanners(
            state = readyState(
                pendingRequests = 2,
                children = listOf(childProfile(1, enrolled = false)),
                notificationsEnabled = false,
            ),
            degradedCapabilities = setOf(ProtectionCapability.CAMERA),
            cameraLimitedAfterBoot = true,
        )
        assertTrue(banners.size <= HOME_BANNER_MAX)
        assertEquals(HOME_BANNER_MAX, banners.size)
        // No condition is duplicated: the banner kinds are distinct.
        assertEquals(banners.map { it.kind }.distinct(), banners.map { it.kind })
    }

    // -------------------------------------------------------------- children

    @Test
    fun theChildrenOverviewCarriesRealProfileData() {
        val summaries = homeChildSummaries(
            readyState(
                children = listOf(
                    childProfile(1, name = "Ali", enrolled = false),
                    childProfile(2, name = "Vali", enrolled = true),
                ),
            ),
        )
        assertEquals(2, summaries.size)
        assertEquals("Ali", summaries[0].name)
        assertEquals("A", summaries[0].initial)
        assertTrue(summaries[0].needsSetup)
        assertFalse(summaries[1].needsSetup)
    }

    @Test
    fun anEmptyAccountProducesNoChildRows() {
        assertTrue(homeChildSummaries(readyState(children = emptyList())).isEmpty())
    }

    @Test
    fun theInitialFallsBackWhenTheNameIsBlank() {
        val summaries = homeChildSummaries(readyState(children = listOf(childProfile(1, name = "   "))))
        assertEquals("?", summaries.single().initial)
    }

    // ----------------------------------------------------------------- today

    @Test
    fun todayCarriesTheFourCoreMetricsForAChildWithData() {
        val metrics = homeTodayMetrics(
            readyState(
                protectionEnabled = true,
                overlay = true, usage = true, accessibility = true,
                children = listOf(childProfile(1)),
                child = childSection(1),
                eyeSafety = EyeSafetySection(
                    1,
                    configured = true,
                    enabled = true,
                    warningAction = null,
                    dangerAction = null,
                ),
            ),
            summary(status = ScreenTimeSummaryStatus.READY, usedMs = 42 * 60_000L, childName = "Ali"),
        )
        assertEquals(
            listOf(
                HomeTodayMetricKind.SCREEN_TIME,
                HomeTodayMetricKind.SCHEDULE,
                HomeTodayMetricKind.EYE_SAFETY,
            ),
            metrics.map { it.kind },
        )
        assertEquals(HomeMetricValue.Duration(42 * 60_000L), metrics.first().value)
        assertEquals("Ali", metrics.first().caption)
    }

    @Test
    fun unavailableUsageIsNeverShownAsAZeroDuration() {
        val metrics = homeTodayMetrics(
            readyState(children = listOf(childProfile(1)), child = childSection(1)),
            summary(status = ScreenTimeSummaryStatus.USAGE_UNAVAILABLE),
        )
        val screenTime = metrics.single { it.kind == HomeTodayMetricKind.SCREEN_TIME }
        assertFalse("must not fabricate 0 min", screenTime.value is HomeMetricValue.Duration)
        assertEquals(QalqonStatusTone.WARNING, screenTime.tone)
    }

    @Test
    fun noTargetIsItsOwnStateAndNotZero() {
        val metrics = homeTodayMetrics(readyState(), summary(status = ScreenTimeSummaryStatus.NO_TARGET))
        val screenTime = metrics.single { it.kind == HomeTodayMetricKind.SCREEN_TIME }
        assertEquals(QalqonStatusTone.INACTIVE, screenTime.tone)
        assertFalse(screenTime.value is HomeMetricValue.Duration)
    }

    @Test
    fun scheduleAndEyeSafetyAreOnlyShownWithAChild() {
        val noChild = homeTodayMetrics(readyState(), summary(status = ScreenTimeSummaryStatus.NO_TARGET))
        assertTrue(noChild.none { it.kind == HomeTodayMetricKind.SCHEDULE })
        assertTrue(noChild.none { it.kind == HomeTodayMetricKind.EYE_SAFETY })
        // The protection-coverage tile needs protection on + at least one protected app.
        assertTrue(noChild.none { it.kind == HomeTodayMetricKind.PROTECTION_SUMMARY })
    }

    @Test
    fun theProtectionTileAppearsOnlyWhenRelevantAndNeverRepeatsTheHeroCopy() {
        // Not relevant while protection is off …
        val off = homeTodayMetrics(
            readyState(protectionEnabled = false, protectedAppsCount = 3),
            summary(status = ScreenTimeSummaryStatus.NO_TARGET),
        )
        assertTrue(off.none { it.kind == HomeTodayMetricKind.PROTECTION_SUMMARY })

        // … and not relevant while nothing is protected.
        val noneProtected = homeTodayMetrics(
            readyState(protectionEnabled = true, protectedAppsCount = 0),
            summary(status = ScreenTimeSummaryStatus.NO_TARGET),
        )
        assertTrue(noneProtected.none { it.kind == HomeTodayMetricKind.PROTECTION_SUMMARY })

        // Relevant with protection on and a real coverage count.
        val relevant = homeTodayMetrics(
            readyState(protectionEnabled = true, protectedAppsCount = 4),
            summary(status = ScreenTimeSummaryStatus.NO_TARGET),
        ).single { it.kind == HomeTodayMetricKind.PROTECTION_SUMMARY }
        assertEquals(HomeMetricValue.Text(R.string.home_protection_apps_count, 4), relevant.value)

        // It must not carry the hero's on/off status copy.
        val heroLabels = listOf(
            R.string.dashboard_protection_on,
            R.string.dashboard_protection_off,
        )
        assertTrue(relevant.labelRes !in heroLabels)
        assertTrue((relevant.value as HomeMetricValue.Text).res !in heroLabels)
    }

    @Test
    fun anActiveScheduleIsShownWithItsModeAsTheValue() {
        val metrics = homeTodayMetrics(
            readyState(
                children = listOf(childProfile(1)),
                child = childSection(1),
                schedule = activeSchedule("Dars"),
            ),
            summary(status = ScreenTimeSummaryStatus.NO_TARGET),
        )
        val schedule = metrics.single { it.kind == HomeTodayMetricKind.SCHEDULE }
        assertEquals("Dars", schedule.caption)
        assertEquals(QalqonStatusTone.ACTIVE, schedule.tone)
    }

    @Test
    fun everyTodayMetricHasALabel() {
        val metrics = homeTodayMetrics(
            readyState(
                children = listOf(childProfile(1)),
                child = childSection(1),
                eyeSafety = EyeSafetySection(
                    1,
                    configured = false,
                    enabled = false,
                    warningAction = null,
                    dangerAction = null,
                ),
            ),
            summary(status = ScreenTimeSummaryStatus.READY, usedMs = 0L, childName = "Ali"),
        )
        assertTrue(metrics.isNotEmpty())
        metrics.forEach { assertTrue(it.labelRes != 0) }
    }

    // --------------------------------------------------------- quick actions

    @Test
    fun quickActionsAreExactlyTheThreeEverydayDestinations() {
        // "Manage children" is deliberately absent: the bottom-navigation Children tab owns it.
        val actions = homeQuickActions(
            readyState(
                pendingRequests = 1,
                children = listOf(childProfile(1, enrolled = false)),
                protectionEnabled = true,
                protectedAppsCount = 0,
            ),
        )
        assertEquals(
            listOf(
                HomeQuickAction.PROTECTED_APPS,
                HomeQuickAction.SCREEN_TIME,
                HomeQuickAction.RULES,
            ),
            actions,
        )
        assertTrue("was ${actions.size}", actions.size <= HOME_QUICK_ACTIONS_MAX)
    }

    @Test
    fun quickActionsNeverIncludeManageChildren() {
        // Regression: the duplicate of the bottom tab must never come back.
        HomeQuickAction.entries.forEach { action ->
            assertFalse(action.name.contains("MANAGE_CHILDREN"))
        }
    }

    @Test
    fun everyQuickActionHasALabel() {
        HomeQuickAction.entries.forEach { assertTrue(homeQuickActionLabelRes(it) != 0) }
    }

    // ---------------------------------------------------------------- helpers

    private fun readyState(
        protectionEnabled: Boolean = false,
        protectionState: ProtectionState = ProtectionState.UNPROTECTED,
        overlay: Boolean = false,
        usage: Boolean = false,
        accessibility: Boolean = false,
        children: List<ChildProfile> = emptyList(),
        child: ChildSection? = null,
        schedule: ScheduleResolution = ScheduleResolution.NoActiveSchedule,
        eyeSafety: EyeSafetySection? = null,
        protectedAppsCount: Int = 0,
        pendingRequests: Int = 0,
        notificationsEnabled: Boolean = true,
    ) = DashboardUiState(
        status = DashboardStatus.READY,
        children = children,
        selectedChildId = child?.childId,
        child = child,
        protectionEnabled = protectionEnabled,
        protectionState = protectionState,
        scheduleResolution = schedule,
        eyeSafety = eyeSafety,
        overlayGranted = overlay,
        usageAccessGranted = usage,
        accessibilityEnabled = accessibility,
        protectedAppsCount = protectedAppsCount,
        pendingRequestCount = pendingRequests,
        notificationsEnabled = notificationsEnabled,
    )

    private fun childProfile(id: Long, name: String = "Child $id", enrolled: Boolean = true) = ChildProfile(
        id = id,
        accountId = 1L,
        childName = name,
        isFaceEnrolled = enrolled,
        restrictionLevel = RestrictionLevel.MEDIUM,
    )

    private fun childSection(id: Long) = ChildSection(
        childId = id,
        name = "Child $id",
        faceEnrolled = true,
        level = RestrictionLevel.MEDIUM,
        policies = PolicyCounts(),
        limits = emptyList(),
        policiesLoading = false,
    )

    private fun activeSchedule(name: String) = ScheduleResolution.ActiveSchedule(
        ScheduleRule(
            id = 1L,
            name = name,
            mode = ScheduleMode.STUDY,
            window = ScheduleWindow(LocalTime.of(9, 0), LocalTime.of(12, 0)),
            days = ScheduleDays(1),
            action = ProtectionAction.ALLOW,
        ),
    )

    private fun summary(
        status: ScreenTimeSummaryStatus,
        usedMs: Long = 0L,
        childName: String? = null,
    ) = ScreenTimeSummaryUiState(
        status = status,
        childId = 1L,
        childName = childName,
        total = if (status == ScreenTimeSummaryStatus.READY) {
            ScreenTimeInfoRow(
                label = ScreenTimeInfoLabel.Total,
                usedMs = usedMs,
                limitMinutes = null,
                remainingMs = null,
                hasLimit = false,
                exceeded = false,
            )
        } else {
            null
        },
    )
}
