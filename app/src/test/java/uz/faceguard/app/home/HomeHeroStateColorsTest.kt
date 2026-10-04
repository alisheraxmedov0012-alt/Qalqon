package uz.faceguard.app.home

import org.junit.Assert.assertEquals
import org.junit.Test
import uz.faceguard.app.core.ui.qalqon.QalqonStatusTone
import uz.faceguard.app.feature.home.HomeHeroSurface
import uz.faceguard.app.feature.home.HomeProtectionStatus
import uz.faceguard.app.feature.home.homeProtectionHeroSurface
import uz.faceguard.app.feature.home.homeProtectionTone

/**
 * The protection hero's state -> colour contract, Compose-free.
 *
 * ON is green, OFF/setup/recovering are amber, a live block is red. Pinned here so a
 * later edit cannot quietly make an unprotected device look neutral/safe.
 */
class HomeHeroStateColorsTest {

    @Test
    fun protectionOnIsGreen() {
        assertEquals(HomeHeroSurface.SUCCESS, homeProtectionHeroSurface(HomeProtectionStatus.ACTIVE))
        assertEquals(QalqonStatusTone.ACTIVE, homeProtectionTone(HomeProtectionStatus.ACTIVE))
    }

    @Test
    fun protectionOffIsAWarningNotNeutral() {
        assertEquals(HomeHeroSurface.WARNING, homeProtectionHeroSurface(HomeProtectionStatus.OFF))
        assertEquals(QalqonStatusTone.WARNING, homeProtectionTone(HomeProtectionStatus.OFF))
    }

    @Test
    fun setupRequiredAndRecoveringAreWarnings() {
        assertEquals(HomeHeroSurface.WARNING, homeProtectionHeroSurface(HomeProtectionStatus.SETUP_REQUIRED))
        assertEquals(HomeHeroSurface.WARNING, homeProtectionHeroSurface(HomeProtectionStatus.RECOVERING))
    }

    @Test
    fun aLiveBlockIsRed() {
        assertEquals(HomeHeroSurface.BLOCKING, homeProtectionHeroSurface(HomeProtectionStatus.BLOCKING))
        assertEquals(QalqonStatusTone.BLOCKING, homeProtectionTone(HomeProtectionStatus.BLOCKING))
    }

    @Test
    fun everyStatusHasAHeroSurface() {
        HomeProtectionStatus.entries.forEach { status ->
            // Non-null and exhaustive: the when() in the mapping has no else branch.
            homeProtectionHeroSurface(status)
            homeProtectionTone(status)
        }
    }
}
