package uz.faceguard.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionServicePolicy

/**
 * Group 6: the pure rule that decides whether background protection (the
 * foreground service) should be running for the current session.
 */
class ProtectionServicePolicyTest {

    @Test
    fun runsForAnEnabledSignedInSession() {
        assertTrue(ProtectionServicePolicy.shouldRun(protectionEnabled = true, accountId = 7L))
    }

    @Test
    fun doesNotRunWhenProtectionIsDisabled() {
        assertFalse(ProtectionServicePolicy.shouldRun(protectionEnabled = false, accountId = 7L))
    }

    @Test
    fun doesNotRunWhenSignedOut() {
        assertFalse(ProtectionServicePolicy.shouldRun(protectionEnabled = true, accountId = null))
        assertFalse(ProtectionServicePolicy.shouldRun(protectionEnabled = false, accountId = null))
    }
}
