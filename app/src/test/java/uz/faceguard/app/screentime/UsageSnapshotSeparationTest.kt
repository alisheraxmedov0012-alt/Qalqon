package uz.faceguard.app.screentime

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.screentime.PackageUsageDelta
import uz.faceguard.app.domain.screentime.ScreenTimeUsageAccounting
import uz.faceguard.app.domain.screentime.UsageSnapshot
import uz.faceguard.app.domain.screentime.UsageSnapshotComparison
import uz.faceguard.app.domain.screentime.UsageSnapshotDeltaEngine
import uz.faceguard.app.domain.screentime.UsageSnapshotRecorder

/**
 * Phase 4 Step 1B-5 (pure JVM): the snapshot/delta layer must stay Android- and Room-free.
 *
 * These classes already load and run on a plain JVM in the tests above, which is itself the
 * proof that no Android class is needed. This pins the boundary so a later change cannot
 * quietly drag `UsageStatsManager`, a `Context` or a Room DAO into the domain.
 */
class UsageSnapshotSeparationTest {

    private val snapshotLayer = listOf(
        UsageSnapshot::class.java,
        UsageSnapshotDeltaEngine::class.java,
        UsageSnapshotRecorder::class.java,
        PackageUsageDelta::class.java,
        PackageUsageDelta.Accumulated::class.java,
        PackageUsageDelta.Unchanged::class.java,
        PackageUsageDelta.NewBaseline::class.java,
        PackageUsageDelta.ResetBaseline::class.java,
        UsageSnapshotComparison::class.java,
        UsageSnapshotComparison.Compared::class.java,
        UsageSnapshotComparison.DifferentObservationWindow::class.java,
        ScreenTimeUsageAccounting::class.java,
    )

    private fun referencedTypeNames(clazz: Class<*>): String = buildString {
        clazz.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { append(it.type.name).append(' ') }
        clazz.declaredMethods.forEach { method ->
            append(method.returnType.name).append(' ')
            method.parameterTypes.forEach { append(it.name).append(' ') }
            append(method.genericReturnType.typeName).append(' ')
            method.genericParameterTypes.forEach { append(it.typeName).append(' ') }
        }
        clazz.declaredConstructors.forEach { ctor ->
            ctor.parameterTypes.forEach { append(it.name).append(' ') }
            ctor.genericParameterTypes.forEach { append(it.typeName).append(' ') }
        }
    }

    @Test
    fun noAndroidDependenciesInDomain() {
        snapshotLayer.forEach { clazz ->
            val referenced = referencedTypeNames(clazz)
            assertTrue(
                "${clazz.simpleName} must not reference Android: $referenced",
                !referenced.contains("android."),
            )
            assertTrue(
                "${clazz.simpleName} must not reference AndroidX/Room: $referenced",
                !referenced.contains("androidx."),
            )
        }
    }

    @Test
    fun noRoomDependencyInDeltaCalculator() {
        val referenced = referencedTypeNames(UsageSnapshotDeltaEngine::class.java)

        listOf("Room", "Dao", "Entity", "SQLite", "dao", "entity").forEach { forbidden ->
            assertTrue(
                "the delta engine must not reference '$forbidden': $referenced",
                !referenced.contains(forbidden),
            )
        }
    }

    @Test
    fun theEngineNeedsOnlyAZoneAndProducesAPureResult() {
        val constructors = UsageSnapshotDeltaEngine::class.java.declaredConstructors
        assertEquals("one way to build the engine", 1, constructors.size)
        assertEquals(
            "the engine depends on a zone and nothing else",
            listOf(java.time.ZoneId::class.java),
            constructors.single().parameterTypes.toList(),
        )

        val compare = UsageSnapshotDeltaEngine::class.java.declaredMethods.single { it.name == "compare" }
        assertEquals(2, compare.parameterCount)
        assertEquals(UsageSnapshot::class.java, compare.parameterTypes[1])
    }

    @Test
    fun theRecorderKnowsOneCollaboratorPairAndNoDevice() {
        val ctor = UsageSnapshotRecorder::class.java.declaredConstructors.single()

        assertEquals(
            "the recorder takes accounting + the engine, never a Context or a usage source",
            listOf(ScreenTimeUsageAccounting::class.java, UsageSnapshotDeltaEngine::class.java),
            ctor.parameterTypes.toList(),
        )
    }

    @Test
    fun theSnapshotModelCarriesNoAccountChildOrCategory() {
        val fields = UsageSnapshot::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()

        // A snapshot is device-wide observation data: it cannot name a child, an account or
        // a policy category, so none can be smuggled in here.
        assertEquals(listOf("cumulativeByPackage", "range", "samples"), fields)
    }
}
