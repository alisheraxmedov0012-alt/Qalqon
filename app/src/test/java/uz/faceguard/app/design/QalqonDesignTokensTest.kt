package uz.faceguard.app.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.theme.QalqonDimens
import uz.faceguard.app.core.theme.QalqonElevation
import uz.faceguard.app.core.theme.QalqonIconSize
import uz.faceguard.app.core.theme.QalqonShapes
import uz.faceguard.app.core.theme.QalqonSizes
import uz.faceguard.app.core.theme.QalqonSpacing

/**
 * QALQON design tokens.
 *
 * The token scale is the contract the whole design system rests on, so its values
 * are pinned here: a change to the rhythm, the radius hierarchy or the
 * accessibility floor becomes a deliberate, visible diff instead of a silent drift
 * back to ad-hoc `16.dp` literals.
 *
 * Pure values (no Android), so these run as ordinary JVM unit tests.
 */
class QalqonDesignTokensTest {

    // ---------------------------------------------------------------- spacing

    @Test
    fun spacingFollowsTheFourEightTwelveRhythm() {
        assertEquals(4f, QalqonSpacing.xs.value)
        assertEquals(8f, QalqonSpacing.sm.value)
        assertEquals(12f, QalqonSpacing.md.value)
        assertEquals(16f, QalqonSpacing.lg.value)
        assertEquals(24f, QalqonSpacing.xl.value)
        assertEquals(32f, QalqonSpacing.xxl.value)
    }

    @Test
    fun spacingIsStrictlyAscending() {
        val values = listOf(
            QalqonSpacing.xs,
            QalqonSpacing.sm,
            QalqonSpacing.md,
            QalqonSpacing.lg,
            QalqonSpacing.xl,
            QalqonSpacing.xxl,
        ).map { it.value }

        assertEquals(values.sorted(), values)
        assertEquals(values.distinct(), values)
    }

    // ------------------------------------------------------------------ shape

    @Test
    fun shapesFollowTheEightTwelveSixteenHierarchy() {
        assertEquals(8f, QalqonShapes.small.value)
        assertEquals(12f, QalqonShapes.medium.value)
        assertEquals(16f, QalqonShapes.large.value)
        assertEquals(999f, QalqonShapes.pill.value)
    }

    @Test
    fun shapeHierarchyIsAscending() {
        assertTrue(QalqonShapes.small.value < QalqonShapes.medium.value)
        assertTrue(QalqonShapes.medium.value < QalqonShapes.large.value)
        assertTrue(QalqonShapes.large.value < QalqonShapes.pill.value)
    }

    // -------------------------------------------------------------- elevation

    @Test
    fun elevationHasExactlyThreeLevelsAndStaysSoft() {
        assertEquals(0f, QalqonElevation.flat.value)
        assertEquals(1f, QalqonElevation.raised.value)
        assertEquals(6f, QalqonElevation.modal.value)
        // A security/productivity UI must not use heavy shadows.
        assertTrue("raised elevation must stay subtle", QalqonElevation.raised.value <= 2f)
    }

    // ------------------------------------------------------------- icon sizes

    @Test
    fun iconSizesFollowTheTokenisedScale() {
        assertEquals(16f, QalqonIconSize.xs.value)
        assertEquals(20f, QalqonIconSize.sm.value)
        assertEquals(24f, QalqonIconSize.md.value)
        assertEquals(32f, QalqonIconSize.lg.value)
    }

    // ------------------------------------------------------------ control size

    @Test
    fun buttonHeightsAreTokenised() {
        assertEquals(36f, QalqonSizes.buttonCompact.value)
        assertEquals(48f, QalqonSizes.buttonDefault.value)
        assertEquals(56f, QalqonSizes.buttonLarge.value)
    }

    @Test
    fun buttonHeightsAreAscending() {
        assertTrue(QalqonSizes.buttonCompact.value < QalqonSizes.buttonDefault.value)
        assertTrue(QalqonSizes.buttonDefault.value < QalqonSizes.buttonLarge.value)
    }

    // ------------------------------------------------------------ accessibility

    @Test
    fun theTouchTargetFloorIsFortyEightDp() {
        assertEquals(48f, QalqonSizes.touchTarget.value)
    }

    @Test
    fun everyInteractiveTokenMeetsTheTouchTargetFloor() {
        // Compact buttons are the one intentional exception (they are always paired
        // with a full-size row), so only the default/large sizes are checked here.
        assertTrue(QalqonSizes.buttonDefault.value >= QalqonSizes.touchTarget.value)
        assertTrue(QalqonSizes.buttonLarge.value >= QalqonSizes.touchTarget.value)
    }

    // ---------------------------------------------------------------- grouping

    @Test
    fun theDimensionGroupExposesTheWholeScale() {
        assertEquals(QalqonSpacing.lg, QalqonDimens.spacing.lg)
        assertEquals(QalqonShapes.medium, QalqonDimens.shapes.medium)
        assertEquals(QalqonElevation.raised, QalqonDimens.elevation.raised)
        assertEquals(QalqonIconSize.md, QalqonDimens.icon.md)
        assertEquals(QalqonSizes.touchTarget, QalqonDimens.sizes.touchTarget)

        assertEquals(QalqonShapes.medium, QalqonDimens.cardCorner)
        assertEquals(QalqonSizes.border, QalqonDimens.cardBorder)
        assertEquals(QalqonSpacing.xl, QalqonDimens.screenPadding)
        assertEquals(QalqonSpacing.lg, QalqonDimens.cardPadding)
        assertEquals(QalqonSpacing.md, QalqonDimens.rowPadding)
    }

    @Test
    fun theCardCornerComesFromTheShapeScaleNotAOneOff() {
        assertTrue(
            "the default card corner must be one of the shape tokens",
            QalqonDimens.cardCorner == QalqonShapes.small ||
                QalqonDimens.cardCorner == QalqonShapes.medium ||
                QalqonDimens.cardCorner == QalqonShapes.large,
        )
    }
}
