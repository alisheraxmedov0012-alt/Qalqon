package uz.faceguard.app.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Stage 4 security regressions that can run on the JVM.
 *
 * Two confirmed Stage 4 issues are pinned here so they cannot silently return:
 *
 * 1. The "full reset" used to clear only part of Room, leaving screen-time history
 *    and per-child schedule/eye-safety configuration on disk after the UI had told
 *    the user all local data was deleted. The test cross-references the entities the
 *    database declares with the delete calls the reset actually makes.
 * 2. `android:allowBackup="false"` no longer covers device-to-device transfers on
 *    Android 12+, and no data-extraction rules were declared. The test asserts the
 *    rules exist and exclude every domain from both a cloud backup and a transfer.
 */
class Stage4DeletionAndBackupTest {

    private val reset = read("app/src/main/java/uz/faceguard/app/data/repository/ResetRepositoryImpl.kt")
    private val database = read("app/src/main/java/uz/faceguard/app/data/db/FaceGuardDatabase.kt")
    private val daos = read("app/src/main/java/uz/faceguard/app/data/db/Daos.kt")
    private val manifest = read("app/src/main/AndroidManifest.xml")

    // ------------------------------------------------------ full reset deletes

    @Test
    fun theFullResetClearsEveryPersistedTable() {
        // One delete statement per table the database declares. `schedule_rules` and
        // `schedule_app_targets` share the schedule DAO, hence the two calls.
        val expectedDeletes = listOf(
            "userAccountDao().deleteAll()",
            "parentProfileDao().deleteAll()",
            "childProfileDao().deleteAll()",
            "protectedAppDao().deleteAll()",
            "activityEventDao().deleteAll()",
            "childAppPolicyDao().deleteAll()",
            "parentRequestDao().deleteAll()",
            "notificationRecordDao().deleteAll()",
            "dailyAppUsageDao().deleteAll()",
            "childScreenTimeLimitDao().deleteAll()",
            "usageSnapshotCheckpointDao().deleteAll()",
            "scheduleDao().deleteAll()",
            "scheduleDao().deleteAllTargets()",
            "childEyeSafetyDao().deleteAll()",
        )
        expectedDeletes.forEach { call ->
            assertTrue("the full reset must clear the table via $call", reset.contains(call))
        }
    }

    @Test
    fun everyDeclaredEntityIsAccountedForByAResetDelete() {
        // Guard against a future table being added to the database but forgotten by
        // the reset: each entity referenced from @Database must be backed by a DAO the
        // reset clears. The entity list is read from the database declaration itself.
        val entityClasses = Regex("""([A-Za-z0-9_]+Entity)::class""")
            .findAll(database.substringAfter("@Database").substringBefore("abstract class"))
            .map { it.groupValues[1] }
            .toList()

        assertTrue("expected the database to declare its entities", entityClasses.isNotEmpty())

        // The DAO accessors the reset calls, by the table each entity maps to.
        val clearedDaos = listOf(
            "userAccountDao", "parentProfileDao", "childProfileDao", "protectedAppDao",
            "activityEventDao", "childAppPolicyDao", "parentRequestDao", "notificationRecordDao",
            "dailyAppUsageDao", "childScreenTimeLimitDao", "usageSnapshotCheckpointDao",
            "scheduleDao", "childEyeSafetyDao",
        )
        // Every entity is covered because the reset names a DAO for each table; assert
        // the reset references each of those DAOs at least once.
        clearedDaos.forEach { dao ->
            assertTrue("the reset must clear $dao", reset.contains("db.$dao()"))
        }
        assertEquals(14, entityClasses.size)
    }

    @Test
    fun theGlobalScheduleAndEyeSafetyDeletesExistAndAreFullResetOnly() {
        // The two tables that previously had no global delete now expose one, and it is
        // documented as reset-only so a per-child path never reaches a global statement.
        assertTrue(daos.contains("suspend fun deleteAll()"))
        assertTrue(daos.contains("suspend fun deleteAllTargets()"))
        assertTrue(
            "the schedule global delete must be documented as full-reset only",
            daos.contains("Full-reset only. Schedules are per child"),
        )
        assertTrue(
            "the eye-safety global delete must be documented as full-reset only",
            daos.contains("Full-reset only: the sole global statement"),
        )
    }

    // ------------------------------------------------------ backup / transfer

    @Test
    fun theManifestDeclaresDataExtractionRules() {
        assertTrue(
            "the application must reference data-extraction rules",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""),
        )
        assertTrue(
            "cloud backup must still be off",
            manifest.contains("android:allowBackup=\"false\""),
        )
    }

    @Test
    fun theDataExtractionRulesExcludeEveryDomainFromBackupAndTransfer() {
        val rules = parse(read("app/src/main/res/xml/data_extraction_rules.xml"))
        val requiredDomains = listOf("root", "file", "database", "sharedpref", "external")

        listOf("cloud-backup", "device-transfer").forEach { section ->
            val nodes = rules.getElementsByTagName(section)
            assertEquals("exactly one <$section> section is expected", 1, nodes.length)
            val element = nodes.item(0) as Element
            val excludes = element.getElementsByTagName("exclude")
            val domains = (0 until excludes.length)
                .map { (excludes.item(it) as Element).getAttribute("domain") }
            requiredDomains.forEach { domain ->
                assertTrue(
                    "<$section> must exclude the '$domain' domain",
                    domains.contains(domain),
                )
            }
        }
    }

    private fun parse(contents: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val stream = contents.byteInputStream()
        return factory.newDocumentBuilder().parse(stream).documentElement
    }

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
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
