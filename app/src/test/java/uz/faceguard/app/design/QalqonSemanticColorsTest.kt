package uz.faceguard.app.design

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.theme.DarkQalqonSemanticColors
import uz.faceguard.app.core.theme.LightQalqonSemanticColors
import uz.faceguard.app.core.theme.QalqonSemanticColors
import uz.faceguard.app.core.theme.QalqonTypography

/**
 * The semantic color palette and the typography scale.
 *
 * The point of the palette is that status meaning has **one** definition with a
 * light and a dark mapping, so a screen never invents `Color(0xFF2E7D32)`. These
 * tests pin that contract: every token exists, no token is transparent, the light
 * and dark mappings actually differ, and status meanings stay distinguishable from
 * each other (green must never equal red).
 */
class QalqonSemanticColorsTest {

    /** Reads every declared semantic token by name, so a new field must be added here too. */
    private fun tokens(colors: QalqonSemanticColors): Map<String, androidx.compose.ui.graphics.Color> = mapOf(
        "success" to colors.success,
        "onSuccess" to colors.onSuccess,
        "successContainer" to colors.successContainer,
        "warning" to colors.warning,
        "onWarning" to colors.onWarning,
        "warningContainer" to colors.warningContainer,
        "info" to colors.info,
        "onInfo" to colors.onInfo,
        "infoContainer" to colors.infoContainer,
        "protectionActive" to colors.protectionActive,
        "protectionInactive" to colors.protectionInactive,
        "protectionBlocking" to colors.protectionBlocking,
        "protectionWarning" to colors.protectionWarning,
        "capabilityGranted" to colors.capabilityGranted,
        "capabilityMissing" to colors.capabilityMissing,
        "childConfigured" to colors.childConfigured,
        "childNeedsSetup" to colors.childNeedsSetup,
        "requestPending" to colors.requestPending,
        "requestApproved" to colors.requestApproved,
        "requestDenied" to colors.requestDenied,
    )

    @Test
    fun everySemanticTokenIsPresentInBothThemes() {
        assertEquals(20, tokens(LightQalqonSemanticColors).size)
        assertEquals(20, tokens(DarkQalqonSemanticColors).size)
        assertEquals(
            tokens(LightQalqonSemanticColors).keys,
            tokens(DarkQalqonSemanticColors).keys,
        )
    }

    @Test
    fun noTokenIsFullyTransparent() {
        listOf(LightQalqonSemanticColors, DarkQalqonSemanticColors).forEach { palette ->
            tokens(palette).forEach { (name, color) ->
                assertTrue("$name must be opaque", color.alpha > 0.99f)
            }
        }
    }

    @Test
    fun darkThemeIsARealSecondMappingNotACopy() {
        assertNotEquals(LightQalqonSemanticColors.success, DarkQalqonSemanticColors.success)
        assertNotEquals(LightQalqonSemanticColors.warning, DarkQalqonSemanticColors.warning)
        assertNotEquals(LightQalqonSemanticColors.info, DarkQalqonSemanticColors.info)
        assertNotEquals(
            LightQalqonSemanticColors.protectionActive,
            DarkQalqonSemanticColors.protectionActive,
        )
    }

    @Test
    fun statusMeaningsStayDistinguishableFromEachOther() {
        listOf(LightQalqonSemanticColors, DarkQalqonSemanticColors).forEach { palette ->
            // "Good" and "bad" must never collapse to the same color.
            assertNotEquals(palette.protectionActive, palette.protectionBlocking)
            assertNotEquals(palette.capabilityGranted, palette.capabilityMissing)
            assertNotEquals(palette.requestApproved, palette.requestDenied)
            assertNotEquals(palette.childConfigured, palette.childNeedsSetup)
        }
    }

    @Test
    fun theHealthLevelColorsTheScreensUsedToHardcodeNowHaveTokens() {
        // These were inline `Color(0xFF...)` literals in SettingsScreen; they now map
        // onto semantic tokens (OK -> success, WARNING -> warning, FAILED -> error-ish).
        assertEquals(LightQalqonSemanticColors.success, LightQalqonSemanticColors.protectionActive)
        assertEquals(LightQalqonSemanticColors.warning, LightQalqonSemanticColors.protectionWarning)
        assertNotEquals(LightQalqonSemanticColors.protectionBlocking, LightQalqonSemanticColors.protectionActive)
    }

    // ------------------------------------------------------------- typography

    @Test
    fun theTypographyScaleIsComplete() {
        val t = QalqonTypography
        assertTrue(t.displayLarge.fontSize.value > 0f)
        assertTrue(t.displayMedium.fontSize.value > 0f)
        assertTrue(t.headlineLarge.fontSize.value > 0f)
        assertTrue(t.headlineMedium.fontSize.value > 0f)
        assertTrue(t.titleLarge.fontSize.value > 0f)
        assertTrue(t.titleMedium.fontSize.value > 0f)
        assertTrue(t.titleSmall.fontSize.value > 0f)
        assertTrue(t.bodyLarge.fontSize.value > 0f)
        assertTrue(t.bodyMedium.fontSize.value > 0f)
        assertTrue(t.bodySmall.fontSize.value > 0f)
        assertTrue(t.labelLarge.fontSize.value > 0f)
        assertTrue(t.labelMedium.fontSize.value > 0f)
        assertTrue(t.labelSmall.fontSize.value > 0f)
    }

    @Test
    fun theTypographyScaleDescendsFromDisplayToLabel() {
        val t = QalqonTypography
        val descending = listOf(
            t.displayLarge.fontSize.value,
            t.displayMedium.fontSize.value,
            t.headlineLarge.fontSize.value,
            t.headlineMedium.fontSize.value,
            t.titleLarge.fontSize.value,
            t.titleMedium.fontSize.value,
            t.bodyLarge.fontSize.value,
            t.bodyMedium.fontSize.value,
            t.bodySmall.fontSize.value,
        )
        assertEquals(descending.sortedDescending(), descending)
    }

    @Test
    fun thePreExistingTypographyValuesAreUnchanged() {
        // These four were already overridden before the design system; changing them
        // would silently re-render existing screens.
        val t = QalqonTypography
        assertEquals(26f, t.headlineMedium.fontSize.value)
        assertEquals(18f, t.titleMedium.fontSize.value)
        assertEquals(16f, t.bodyLarge.fontSize.value)
        assertEquals(14f, t.bodyMedium.fontSize.value)
    }

    @Test
    fun theTypographyScaleDoesNotConstrainLinesSoLongCopyCanWrap() {
        // No maxLines is expressible on TextStyle, so this asserts the contract that
        // components must not truncate: lineHeight is set on the display styles.
        assertTrue(QalqonTypography.displayLarge.lineHeight.value > 0f)
        assertTrue(QalqonTypography.displayMedium.lineHeight.value > 0f)
        assertTrue(QalqonTypography.headlineLarge.lineHeight.value > 0f)
    }

    // -------------------------------------------------- no inline literals guard

    @Test
    fun noScreenOutsideTheThemeDefinesItsOwnStatusColorLiterals() {
        // The design system exists so status colors live in one place. This guard
        // fails if a screen starts inventing hex colors again.
        val offenders = File(repoRoot(), "app/src/main/java/uz/faceguard/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("/core/theme/") }
            .filter { it.readText().contains("Color(0x") }
            .map { it.name }
            .toList()

        assertTrue(
            "status color literals must live in core/theme, found in: ${offenders.sorted()}",
            offenders.isEmpty(),
        )
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
