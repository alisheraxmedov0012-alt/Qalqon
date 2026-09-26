package uz.faceguard.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room (in-memory) tests for [ScheduleDao]: field persistence, account + child isolation
 * and the affected-app relation. Runs on a device / emulator through
 * `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleDaoTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var dao: ScheduleDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.scheduleDao()
    }

    @After
    fun tearDown() = db.close()

    private fun rule(
        accountId: Long = 1L,
        childId: Long = 10L,
        name: String = "Bedtime",
        mode: String = "SLEEP",
        enabled: Boolean = true,
        startMinuteOfDay: Int = 1320,
        endMinuteOfDay: Int = 420,
        daysMask: Int = 0b0000001,
        priority: Int = 5,
        action: String = "HARD_BLOCK",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
    ) = ScheduleRuleEntity(
        accountId = accountId,
        childId = childId,
        name = name,
        mode = mode,
        enabled = enabled,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        daysMask = daysMask,
        priority = priority,
        action = action,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    // ---- schedule CRUD ------------------------------------------------------

    @Test
    fun insert_thenReadBackEveryField() = runBlocking {
        val id = dao.insert(rule(mode = "STUDY", action = "MUTE", priority = -3, enabled = false))

        val stored = dao.schedule(1L, 10L, id)!!
        assertEquals(id, stored.id)
        assertEquals(1L, stored.accountId)
        assertEquals(10L, stored.childId)
        assertEquals("Bedtime", stored.name)
        assertEquals("STUDY", stored.mode)
        assertEquals("MUTE", stored.action)
        assertEquals(-3, stored.priority)
        assertEquals(false, stored.enabled)
        assertEquals(1320, stored.startMinuteOfDay)
        assertEquals(420, stored.endMinuteOfDay)
        assertEquals(0b0000001, stored.daysMask)
        assertEquals(1_000L, stored.createdAt)
        assertEquals(1_000L, stored.updatedAt)
    }

    @Test
    fun crossMidnightWindowPersistsExactly() = runBlocking {
        val id = dao.insert(rule(startMinuteOfDay = 1320, endMinuteOfDay = 420))

        val stored = dao.schedule(1L, 10L, id)!!
        assertEquals(1320, stored.startMinuteOfDay)
        assertEquals(420, stored.endMinuteOfDay)
    }

    @Test
    fun update_replacesTheMutableFields() = runBlocking {
        val id = dao.insert(rule())

        val rows = dao.update(
            accountId = 1L,
            childId = 10L,
            scheduleId = id,
            name = "School night",
            mode = "SCHOOL",
            enabled = false,
            startMinuteOfDay = 1_440 - 1,
            endMinuteOfDay = 420,
            daysMask = 0b0011111,
            priority = 9,
            action = "WARNING",
            updatedAt = 9_000L,
        )

        assertEquals(1, rows)
        val stored = dao.schedule(1L, 10L, id)!!
        assertEquals("School night", stored.name)
        assertEquals("SCHOOL", stored.mode)
        assertEquals("WARNING", stored.action)
        assertEquals(9, stored.priority)
        assertEquals(false, stored.enabled)
        assertEquals(1439, stored.startMinuteOfDay)
        assertEquals(0b0011111, stored.daysMask)
        assertEquals("createdAt is unchanged by an update", 1_000L, stored.createdAt)
        assertEquals(9_000L, stored.updatedAt)
    }

    @Test
    fun update_returnsZeroForAnUnknownId() = runBlocking {
        val rows = dao.update(
            accountId = 1L, childId = 10L, scheduleId = 999L, name = "x", mode = "NORMAL",
            enabled = true, startMinuteOfDay = 0, endMinuteOfDay = 1, daysMask = 1,
            priority = 0, action = "ALLOW", updatedAt = 1L,
        )

        assertEquals(0, rows)
    }

    @Test
    fun observeAndListAreOrderedByCreation() = runBlocking {
        val first = dao.insert(rule(name = "First", createdAt = 1_000L))
        val second = dao.insert(rule(name = "Second", createdAt = 2_000L))

        assertEquals(listOf("First", "Second"), dao.schedules(1L, 10L).map { it.name })
        assertEquals(listOf("First", "Second"), dao.observeSchedules(1L, 10L).first().map { it.name })
        assertEquals(first, dao.schedules(1L, 10L).first().id)
        assertEquals(second, dao.schedules(1L, 10L).last().id)
    }

    @Test
    fun delete_removesOnlyThatSchedule() = runBlocking {
        val keep = dao.insert(rule(name = "Keep"))
        val drop = dao.insert(rule(name = "Drop"))

        val rows = dao.delete(1L, 10L, drop)

        assertEquals(1, rows)
        assertNull(dao.schedule(1L, 10L, drop))
        assertEquals("Keep", dao.schedule(1L, 10L, keep)!!.name)
    }

    @Test
    fun deleteForChild_removesEveryScheduleOfThatChildOnly() = runBlocking {
        dao.insert(rule(childId = 10L, name = "A"))
        dao.insert(rule(childId = 10L, name = "B"))
        dao.insert(rule(childId = 11L, name = "C"))

        val rows = dao.deleteForChild(1L, 10L)

        assertEquals(2, rows)
        assertEquals(emptyList<ScheduleRuleEntity>(), dao.schedules(1L, 10L))
        assertEquals(listOf("C"), dao.schedules(1L, 11L).map { it.name })
    }

    // ---- account isolation --------------------------------------------------

    @Test
    fun aScheduleIsNotVisibleToAnotherAccount() = runBlocking {
        val id = dao.insert(rule(accountId = 1L, childId = 10L, name = "Mine"))

        assertNull("account 2 must not read account 1's schedule", dao.schedule(2L, 10L, id))
        assertEquals(emptyList<ScheduleRuleEntity>(), dao.schedules(2L, 10L))
    }

    @Test
    fun anotherAccountCannotUpdateOrDeleteASchedule() = runBlocking {
        val id = dao.insert(rule(accountId = 1L, childId = 10L, name = "Mine"))

        val updated = dao.update(
            accountId = 2L, childId = 10L, scheduleId = id, name = "Hijacked", mode = "NORMAL",
            enabled = true, startMinuteOfDay = 0, endMinuteOfDay = 1, daysMask = 1,
            priority = 0, action = "ALLOW", updatedAt = 1L,
        )
        val deleted = dao.delete(2L, 10L, id)

        assertEquals(0, updated)
        assertEquals(0, deleted)
        assertEquals("Mine", dao.schedule(1L, 10L, id)!!.name)
    }

    // ---- child isolation ----------------------------------------------------

    @Test
    fun aScheduleIsNotVisibleToAnotherChild() = runBlocking {
        val id = dao.insert(rule(accountId = 1L, childId = 10L, name = "Sibling's"))

        assertNull("child 11 must not read child 10's schedule", dao.schedule(1L, 11L, id))
        assertEquals(emptyList<ScheduleRuleEntity>(), dao.schedules(1L, 11L))
    }

    @Test
    fun anotherChildCannotUpdateOrDeleteASchedule() = runBlocking {
        val id = dao.insert(rule(accountId = 1L, childId = 10L, name = "Sibling's"))

        val updated = dao.update(
            accountId = 1L, childId = 11L, scheduleId = id, name = "Hijacked", mode = "NORMAL",
            enabled = true, startMinuteOfDay = 0, endMinuteOfDay = 1, daysMask = 1,
            priority = 0, action = "ALLOW", updatedAt = 1L,
        )
        val deleted = dao.delete(1L, 11L, id)

        assertEquals(0, updated)
        assertEquals(0, deleted)
        assertEquals("Sibling's", dao.schedule(1L, 10L, id)!!.name)
    }

    // ---- affected-app membership -------------------------------------------

    @Test
    fun targets_areInsertedReadAndReplacedPerScope() = runBlocking {
        val id = dao.insert(rule())

        dao.insertTargets(
            listOf(
                ScheduleAppTargetEntity(1L, 10L, id, "com.b"),
                ScheduleAppTargetEntity(1L, 10L, id, "com.a"),
            ),
        )

        assertEquals(listOf("com.a", "com.b"), dao.targetPackages(1L, 10L, id))
        assertEquals(listOf("com.a", "com.b"), dao.observeTargetPackages(1L, 10L, id).first())

        // Replace: delete then insert, exactly what the repository does in one transaction.
        dao.deleteTargets(1L, 10L, id)
        dao.insertTargets(listOf(ScheduleAppTargetEntity(1L, 10L, id, "com.c")))

        assertEquals(listOf("com.c"), dao.targetPackages(1L, 10L, id))

        // Empty target set is legitimate.
        dao.deleteTargets(1L, 10L, id)
        assertEquals(emptyList<String>(), dao.targetPackages(1L, 10L, id))
    }

    @Test
    fun aDuplicateTargetIsTheSameRelation() = runBlocking {
        val id = dao.insert(rule())

        dao.insertTargets(listOf(ScheduleAppTargetEntity(1L, 10L, id, "com.a")))
        dao.insertTargets(listOf(ScheduleAppTargetEntity(1L, 10L, id, "com.a")))

        assertEquals(listOf("com.a"), dao.targetPackages(1L, 10L, id))
    }

    @Test
    fun targetsAreIsolatedByAccountChildAndSchedule() = runBlocking {
        val a = dao.insert(rule(accountId = 1L, childId = 10L, name = "A"))
        val b = dao.insert(rule(accountId = 1L, childId = 10L, name = "B"))

        dao.insertTargets(
            listOf(
                ScheduleAppTargetEntity(1L, 10L, a, "com.a"),
                ScheduleAppTargetEntity(1L, 10L, b, "com.b"),
                ScheduleAppTargetEntity(1L, 11L, a, "com.c"),
                ScheduleAppTargetEntity(2L, 10L, a, "com.d"),
            ),
        )

        assertEquals(listOf("com.a"), dao.targetPackages(1L, 10L, a))
        assertEquals(listOf("com.b"), dao.targetPackages(1L, 10L, b))
        assertEquals(listOf("com.c"), dao.targetPackages(1L, 11L, a))
        assertEquals(listOf("com.d"), dao.targetPackages(2L, 10L, a))
    }

    @Test
    fun deleteTargets_removesOnlyThatSchedulesRows() = runBlocking {
        val a = dao.insert(rule(name = "A"))
        val b = dao.insert(rule(name = "B"))
        dao.insertTargets(
            listOf(
                ScheduleAppTargetEntity(1L, 10L, a, "com.a"),
                ScheduleAppTargetEntity(1L, 10L, b, "com.b"),
            ),
        )

        val rows = dao.deleteTargets(1L, 10L, a)

        assertEquals(1, rows)
        assertEquals(emptyList<String>(), dao.targetPackages(1L, 10L, a))
        assertEquals(listOf("com.b"), dao.targetPackages(1L, 10L, b))
    }

    @Test
    fun theCompositeKeyIsEnforcedAtTheSqlLevel() = runBlocking {
        val id = dao.insert(rule())
        val raw = db.openHelper.writableDatabase
        raw.execSQL("INSERT INTO schedule_app_targets VALUES (1, 10, $id, 'com.a')")

        val duplicate = runCatching {
            raw.execSQL("INSERT INTO schedule_app_targets VALUES (1, 10, $id, 'com.a')")
        }

        assertTrue("the same (account, child, schedule, package) cannot exist twice", duplicate.isFailure)
    }
}
