package uz.faceguard.app.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.permission.openFirstAvailable

/**
 * Stage 6: the ordered settings-intent launch contract.
 *
 * The OEM compatibility layer produces a *list* of candidate settings pages (OEM
 * pages first, a generic fallback last). This pins the resolution rules: the first
 * page with a handler wins, an unresolved candidate is skipped, and "nothing
 * resolves" is a normal false — never a crash or a dead end.
 */
class Stage6SettingsIntentFallbackTest {

    private class Recorder(private val resolves: (String) -> Boolean) {
        val attempted = mutableListOf<String>()
        val starter: (String) -> Boolean = { page ->
            attempted += page
            resolves(page)
        }
    }

    @Test
    fun theFirstResolvingIntentWins() {
        val recorder = Recorder { it == "oem" }
        val opened = openFirstAvailable(listOf("oem", "generic", "details")) { recorder.starter(it) }

        assertTrue(opened)
        assertEquals(listOf("oem"), recorder.attempted)
    }

    @Test
    fun anUnresolvedOemCandidate_isSkippedForTheNext() {
        val recorder = Recorder { it == "generic" }
        val opened = openFirstAvailable(listOf("oem", "generic", "details")) { recorder.starter(it) }

        assertTrue(opened)
        assertEquals(listOf("oem", "generic"), recorder.attempted)
    }

    @Test
    fun theUniversalFallbackIsUsedWhenNothingElseResolves() {
        val recorder = Recorder { it == "details" }
        val opened = openFirstAvailable(listOf("oem", "generic", "details")) { recorder.starter(it) }

        assertTrue(opened)
        assertEquals(listOf("oem", "generic", "details"), recorder.attempted)
    }

    @Test
    fun noHandlerAnywhere_isFalseAndNeverThrows() {
        val recorder = Recorder { false }
        val opened = openFirstAvailable(listOf("a", "b", "c")) { recorder.starter(it) }

        assertFalse(opened)
        assertEquals(listOf("a", "b", "c"), recorder.attempted)
    }

    @Test
    fun anEmptyCandidateList_isFalseAndStartsNothing() {
        var started = false
        val opened = openFirstAvailable(emptyList<String>()) { started = true; true }
        assertFalse(opened)
        assertFalse(started)
    }

    @Test
    fun aListIsNeverExhaustedAfterTheFirstSuccess() {
        // Later candidates must not be attempted once one has launched.
        val recorder = Recorder { true }
        openFirstAvailable(listOf("a", "b", "c")) { recorder.starter(it) }
        assertEquals(listOf("a"), recorder.attempted)
    }
}
