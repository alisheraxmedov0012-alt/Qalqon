package uz.faceguard.app.child

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX redesign Phase 4: the Children/Child Detail redesign must not remove any
 * existing child capability or disturb the layers it does not own.
 *
 * Source-level guards, pure JVM: a later edit that drops a child action, re-invents a
 * child route, or reaches into the data layer fails here.
 */
class ChildFeatureRegressionTest {

    private val childScreens = read("feature/child/ChildProfilesScreen.kt")
    private val childDetail = read("feature/child/ChildDetailScreen.kt")
    private val navGraph = read("navigation/NavGraph.kt")

    @Test
    fun theChildrenListKeepsAddEditDeleteAndFaceEnrollment() {
        listOf(
            "fun openAddDialog()",
            "fun openEditDialog(",
            "fun requestDelete(",
            "fun confirmDelete()",
            "fun saveDialog()",
        ).forEach { fn ->
            assertTrue("ChildProfilesViewModel lost $fn", childScreens.contains(fn))
        }
        // Face enrollment is still reachable from the list.
        assertTrue(childScreens.contains("onEnrollChild"))
    }

    @Test
    fun theChildDetailHubLinksEveryChildScopedDestination() {
        listOf(
            "Routes.childPolicy(",
            "Routes.childScreenTime(",
            "Routes.childSchedules(",
            "Routes.childEyeSafety(",
            "Routes.childFaceEnrollment(",
        ).forEach { route ->
            assertTrue("the hub no longer reaches $route", navGraph.contains(route))
        }
    }

    @Test
    fun everyExistingChildScopedRouteIsStillDeclared() {
        listOf(
            "child_policy/{childId}",
            "child_schedules/{childId}",
            "child_eye_safety/{childId}",
            "child_face_enrollment/{childId}",
        ).forEach { route ->
            assertTrue("route '$route' was dropped", navGraph.contains(route))
        }
    }

    @Test
    fun homeStillOpensAChildThroughItsExistingCallback() {
        // Phase 3's callback name is preserved; only its destination changed.
        assertTrue(navGraph.contains("onOpenChildPolicy"))
        assertTrue(navGraph.contains("Routes.childDetail(childId)"))
    }

    @Test
    fun theChildrenTabOpensTheChildDetailHub() {
        assertTrue(navGraph.contains("onOpenChild = "))
    }

    @Test
    fun theNewChildScreensDoNotTouchPersistenceOrBusinessLogic() {
        listOf("ChildProfilesScreen.kt", "ChildDetailScreen.kt", "ChildDetailPresentation.kt", "ChildrenPresentation.kt")
            .forEach { file ->
                val source = read("feature/child/$file")
                assertFalse("$file must not import the data layer", source.contains("import uz.faceguard.app.data."))
                assertFalse("$file must not reach the protection engine", source.contains("ProtectionEngine"))
                assertFalse("$file must not run raw queries", source.contains("rawQuery"))
            }
    }

    @Test
    fun theChildScopedScreenTimeViewReusesTheExistingScreen() {
        // The screen-time destination is a mode of the existing App rules screen, not a
        // second screen with duplicated limit logic.
        assertTrue(read("feature/policy/ChildPolicyScreen.kt").contains("focusScreenTime"))
        assertFalse("a second screen-time screen must not be introduced", navGraph.contains("ChildScreenTimeScreen"))
    }

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), "app/src/main/java/uz/faceguard/app/$relativePath")
        assertTrue("missing file: ${file.path}", file.isFile)
        return file.readText()
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
