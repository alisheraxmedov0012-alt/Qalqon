package uz.faceguard.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.util.Validation
import uz.faceguard.app.feature.home.homeGreetingName

/**
 * Registered-name bug: the Home greeting must use the parent's real name and must
 * never present a phone number as that name.
 *
 * Covers both the source (name validation rejects a phone-like value, so it can no
 * longer be persisted) and the presentation guard (an already-stored phone-like value
 * falls back to the neutral greeting). Pure JVM.
 */
class HomeGreetingNameTest {

    // ------------------------------------------------------------------- presentation

    @Test
    fun aRegisteredNameIsUsedVerbatim() {
        assertEquals("Alisher", homeGreetingName("Alisher"))
        assertEquals("Alisher", homeGreetingName("  Alisher  "))
        assertEquals("Ali Valiyev", homeGreetingName("Ali Valiyev"))
    }

    @Test
    fun aBlankNameFallsBackToTheNeutralGreeting() {
        assertNull(homeGreetingName(null))
        assertNull(homeGreetingName(""))
        assertNull(homeGreetingName("   "))
    }

    @Test
    fun aPhoneNumberIsNeverUsedAsTheGreetingName() {
        // The reported bug: "Salom, 772582504".
        assertNull(homeGreetingName("772582504"))
        assertNull(homeGreetingName("+998 90 123 45 67"))
        assertNull(homeGreetingName("90-123-45-67"))
        assertNull(homeGreetingName("(90) 123 45 67"))
    }

    @Test
    fun aNameThatMerelyContainsDigitsIsStillAName() {
        // A legitimate name with a number is not a phone number.
        assertEquals("Ali 2", homeGreetingName("Ali 2"))
    }

    // ------------------------------------------------------------------------- source

    @Test
    fun aRealNamePassesRegistrationValidation() {
        assertTrue(Validation.isValidFullName("Alisher"))
        assertTrue(Validation.isValidFullName("Ali Valiyev"))
        assertTrue(Validation.isValidFullName("Алишер"))
    }

    @Test
    fun aPhoneNumberIsRejectedByRegistrationValidation() {
        assertFalse(Validation.isValidFullName("772582504"))
        assertFalse(Validation.isValidFullName("+998 90 123 45 67"))
        assertFalse(Validation.isValidFullName("90-123-45-67"))
    }

    @Test
    fun aTooShortNameIsStillRejected() {
        assertFalse(Validation.isValidFullName("Al"))
        assertFalse(Validation.isValidFullName(""))
    }

    @Test
    fun phoneLikeDetectionIsConservative() {
        assertTrue(Validation.isPhoneLike("998901234567"))
        assertFalse(Validation.isPhoneLike("Alisher"))
        assertFalse(Validation.isPhoneLike(""))
        // A single letter makes it a name, not a phone.
        assertFalse(Validation.isPhoneLike("Ali 90"))
    }
}
