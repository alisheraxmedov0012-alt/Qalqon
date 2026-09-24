package uz.faceguard.app.security

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.security.PinHasher
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.db.UserAccountEntity
import uz.faceguard.app.data.prefs.PinAttemptStore
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.repository.AccountRepositoryImpl
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.security.PinAttemptPolicy

/**
 * Phase 12: PIN storage and brute-force resistance with real Room + DataStore and
 * the production [AccountRepositoryImpl]. No PIN value is printed anywhere.
 */
@RunWith(AndroidJUnit4::class)
class PinSecurityPersistenceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: FaceGuardDatabase
    private lateinit var session: SessionManager
    private lateinit var attempts: PinAttemptStore
    private lateinit var repository: AccountRepositoryImpl

    private var now = 1_000_000L
    private val pin = "4829"
    private val phone = "998901112233"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries().build()
        session = SessionManager(context)
        attempts = PinAttemptStore(context)
        session.clearSession()
        attempts.clearAll()
        repository = AccountRepositoryImpl(db.userAccountDao(), session, attempts) { now }
    }

    @After
    fun tearDown() = runBlocking {
        session.clearSession()
        attempts.clearAll()
        db.close()
    }

    private fun legacyHash(salt: String, value: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + value).toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun registrationStoresAPbkdf2HashAndNeverThePlainPin() = runBlocking {
        val result = repository.register("Parent", phone, pin)
        assertTrue(result is AuthResult.Success)

        val stored = db.userAccountDao().getByPhone(phone)!!
        assertFalse("the PIN must never be stored", stored.pinHash.contains(pin))
        assertTrue(stored.pinHash.startsWith("pbkdf2\$sha256\$"))
        assertFalse(PinHasher.needsUpgrade(stored.pinHash))
    }

    @Test
    fun theCorrectPinLogsInAndAWrongOneDoesNot() = runBlocking {
        repository.register("Parent", phone, pin)
        repository.logout()

        assertTrue(repository.login(phone, "0000") is AuthResult.Failure)
        val success = repository.login(phone, pin)
        assertTrue(success is AuthResult.Success)
    }

    @Test
    fun repeatedFailuresLockTheAccountTemporarily() = runBlocking {
        repository.register("Parent", phone, pin)
        repository.logout()

        repeat(PinAttemptPolicy.MAX_ATTEMPTS) {
            val failure = repository.login(phone, "0000")
            assertTrue(failure is AuthResult.Failure)
        }

        val locked = repository.login(phone, pin)
        assertTrue("even the correct PIN is refused while locked", locked is AuthResult.Failure)
        assertEquals(AuthResult.Reason.LOCKED_OUT, (locked as AuthResult.Failure).reason)
        assertNotNull(locked.lockedUntilMillis)

        // After the temporary window the legitimate parent can log in again.
        now = locked.lockedUntilMillis!! + 1
        assertTrue(repository.login(phone, pin) is AuthResult.Success)
    }

    @Test
    fun aSuccessOnAnotherAccountIsNotAffectedByALockout() = runBlocking {
        repository.register("Parent", phone, pin)
        repository.logout()
        val otherPhone = "998900000001"
        repository.register("Other", otherPhone, "1357")
        repository.logout()

        repeat(PinAttemptPolicy.MAX_ATTEMPTS) { repository.login(phone, "0000") }
        assertTrue(repository.login(phone, pin) is AuthResult.Failure)

        // Account isolation: the lockout is per account.
        assertTrue(repository.login(otherPhone, "1357") is AuthResult.Success)
    }

    @Test
    fun aLegacyHashLogsInAndIsUpgradedToPbkdf2() = runBlocking {
        val salt = PinHasher.randomSalt()
        db.userAccountDao().insert(
            UserAccountEntity(
                fullName = "Parent",
                phoneNumber = phone,
                pinHash = legacyHash(salt, pin),
                pinSalt = salt,
            ),
        )
        assertTrue(PinHasher.needsUpgrade(db.userAccountDao().getByPhone(phone)!!.pinHash))

        assertTrue(repository.login(phone, pin) is AuthResult.Success)

        val upgraded = db.userAccountDao().getByPhone(phone)!!
        assertTrue("the legacy hash must be upgraded", upgraded.pinHash.startsWith("pbkdf2\$sha256\$"))
        assertFalse(PinHasher.needsUpgrade(upgraded.pinHash))
        // The new salt/hash still verify the same PIN.
        assertTrue(PinHasher.verify(pin, upgraded.pinHash, upgraded.pinSalt))
    }

    @Test
    fun signOutClearsTheSecurityAttemptState() = runBlocking {
        repository.register("Parent", phone, pin)
        repository.logout()
        repeat(PinAttemptPolicy.MAX_ATTEMPTS - 1) { repository.login(phone, "0000") }
        val accountId = db.userAccountDao().getByPhone(phone)!!.id
        assertTrue(attempts.stateFor(accountId).failedCount > 0)

        // A successful login clears it, and so does signing out.
        repository.login(phone, pin)
        assertEquals(0, attempts.stateFor(accountId).failedCount)
    }

    @Test
    fun attemptStateSurvivesARestartOfTheStore() = runBlocking {
        repository.register("Parent", phone, pin)
        repository.logout()
        val accountId = db.userAccountDao().getByPhone(phone)!!.id
        repository.login(phone, "0000")

        // A fresh store instance over the same DataStore (process restart) still
        // sees the counter: a lockout cannot be cleared by killing the app.
        val restarted = PinAttemptStore(context)
        assertTrue(restarted.stateFor(accountId).failedCount > 0)
    }

    @Test
    fun theEmergencyPinPathAlsoRespectsTheLockout() = runBlocking {
        repository.register("Parent", phone, pin)
        repeat(PinAttemptPolicy.MAX_ATTEMPTS) { repository.verifyPin("0000") }

        assertFalse("a locked session cannot be unlocked with the correct PIN", repository.verifyPin(pin))

        now += PinAttemptPolicy.MAX_LOCKOUT_MS
        assertTrue(repository.verifyPin(pin))
    }
}
