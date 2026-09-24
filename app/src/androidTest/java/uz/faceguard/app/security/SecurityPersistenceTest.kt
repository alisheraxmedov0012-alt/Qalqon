package uz.faceguard.app.security

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.security.AesGcmSecureCrypto
import uz.faceguard.app.core.security.AndroidKeystoreKeyProvider
import uz.faceguard.app.core.security.CryptoResult
import uz.faceguard.app.core.security.KeystoreBiometricTemplateCipher
import uz.faceguard.app.core.security.SecurityState
import uz.faceguard.app.core.security.SecurityStateHolder
import uz.faceguard.app.data.db.ChildProfileEntity
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.prefs.PinAttemptStore
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.data.prefs.settingsDataStore
import uz.faceguard.app.data.repository.BiometricMigrationService
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.data.repository.ResetRepositoryImpl
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.security.TemplateRecovery

/** Real encryption key alias used only by this test (removed in tearDown). */
private const val TEST_KEY_ALIAS = "qalqon.test.phase12"

/**
 * Phase 12: biometric encryption at rest, with the *real* Android Keystore, real
 * Room and the production repositories. Hardware-backed key attestation is not
 * verified here (an emulator cannot prove it) — see the report.
 */
@RunWith(AndroidJUnit4::class)
class SecurityPersistenceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: FaceGuardDatabase
    private lateinit var keyProvider: AndroidKeystoreKeyProvider
    private lateinit var securityState: SecurityStateHolder
    private lateinit var cipher: KeystoreBiometricTemplateCipher
    private lateinit var childRepository: ChildProfileRepositoryImpl
    private lateinit var migration: BiometricMigrationService

    private val plainTemplate = "AACAPwAAAAAAAAAAAACAPwAAAAAAAAAA"

    @Before
    fun setUp() = runBlocking {
        context.deleteDatabase(DB_NAME)
        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries().build()
        keyProvider = AndroidKeystoreKeyProvider(TEST_KEY_ALIAS)
        keyProvider.deleteKey()
        securityState = SecurityStateHolder()
        cipher = KeystoreBiometricTemplateCipher(AesGcmSecureCrypto(keyProvider), securityState)
        childRepository = ChildProfileRepositoryImpl(db.childProfileDao(), cipher)
        migration = BiometricMigrationService(
            parentProfileDao = db.parentProfileDao(),
            childProfileDao = db.childProfileDao(),
            templateCipher = cipher,
            securityState = securityState,
        )
    }

    @After
    fun tearDown() {
        keyProvider.deleteKey()
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun theKeystoreKeyEncryptsAndDecryptsAroundTrip() {
        val crypto = AesGcmSecureCrypto(keyProvider)

        val encrypted = crypto.encrypt("embedding".toByteArray())
        assertTrue(encrypted is CryptoResult.Success)
        val decrypted = crypto.decrypt((encrypted as CryptoResult.Success).bytes)

        assertTrue(decrypted is CryptoResult.Success)
        assertEquals("embedding", String((decrypted as CryptoResult.Success).bytes))
        // The key stays inside the Keystore: only a handle is ever exposed.
        assertNotNull(keyProvider.key())
    }

    @Test
    fun anInvalidatedKeyRequiresRecoveryInsteadOfSilentReencryption() {
        val stored = cipher.protect(plainTemplate)!!

        // Simulates the platform invalidating/removing the key (factory reset of
        // credentials, restore on another device, explicit key deletion).
        keyProvider.deleteKey()

        assertEquals(TemplateRecovery.Unavailable, cipher.recover(stored))
        assertEquals(SecurityState.RECOVERY_REQUIRED, securityState.state.value)
    }

    @Test
    fun anEnrollmentIsStoredEncryptedAndNeverPlaintext() = runBlocking {
        val childId = childRepository.addChild(1L, "Vali", RestrictionLevel.HIGH)

        childRepository.saveFaceEnrollment(1L, childId, plainTemplate)

        val raw = db.childProfileDao().observeAll(1L).first().single().faceTemplateRef
        assertNotNull(raw)
        assertTrue("stored form must be the encrypted envelope", raw!!.startsWith("v1:"))
        assertFalse("the plaintext template must not be persisted", raw.contains(plainTemplate))

        // The domain view still yields the transient plaintext for recognition.
        val child = childRepository.observeChildren(1L).first().single()
        assertEquals(plainTemplate, child.faceTemplateRef)
        assertEquals(EnrollmentStatus.ENROLLED, child.enrollmentStatus)
    }

    @Test
    fun aLegacyPlaintextRowIsMigratedWithoutDataLossAndIdempotently() = runBlocking {
        // Simulates a pre-Phase-12 database row.
        db.childProfileDao().insert(
            ChildProfileEntity(
                accountId = 1L,
                childName = "Vali",
                isFaceEnrolled = true,
                faceTemplateRef = plainTemplate,
                restrictionLevel = RestrictionLevel.HIGH.name,
                enrollmentStatus = EnrollmentStatus.ENROLLED.name,
            ),
        )
        val legacyId = db.childProfileDao().observeAll(1L).first().single().id

        // Still readable before the sweep (legacy path), and flagged.
        assertEquals(plainTemplate, childRepository.observeChildren(1L).first().single().faceTemplateRef)
        assertFalse(cipher.isProtected(plainTemplate))

        val migrated = migration.migrateLegacyTemplates()
        assertEquals(1, migrated)

        val raw = db.childProfileDao().observeAll(1L).first().single().faceTemplateRef!!
        assertTrue(raw.startsWith("v1:"))
        assertFalse(raw.contains(plainTemplate))
        // Ownership and enrollment metadata survive the migration.
        val entity = db.childProfileDao().observeAll(1L).first().single()
        assertEquals(legacyId, entity.id)
        assertEquals(1L, entity.accountId)
        assertEquals("Vali", entity.childName)
        assertEquals(EnrollmentStatus.ENROLLED.name, entity.enrollmentStatus)
        assertEquals(plainTemplate, childRepository.observeChildren(1L).first().single().faceTemplateRef)

        // Idempotent.
        assertEquals(0, migration.migrateLegacyTemplates())
    }

    @Test
    fun aMissingKeyFailsClosedAndWritesNothing() = runBlocking {
        val childId = childRepository.addChild(1L, "Vali", RestrictionLevel.HIGH)

        // A cipher whose key is permanently unavailable.
        val brokenRepo = ChildProfileRepositoryImpl(
            db.childProfileDao(),
            KeystoreBiometricTemplateCipher(AesGcmSecureCrypto(keyProvider = { null }), securityState),
        )
        brokenRepo.saveFaceEnrollment(1L, childId, plainTemplate)

        assertNull(
            "no plaintext may be written when protection is unavailable",
            db.childProfileDao().observeAll(1L).first().single().faceTemplateRef,
        )
    }

    @Test
    fun anotherAccountsRowIsNotTouchedByMigrationOrReads() = runBlocking {
        db.childProfileDao().insert(
            ChildProfileEntity(
                accountId = 2L,
                childName = "Ali",
                isFaceEnrolled = true,
                faceTemplateRef = plainTemplate,
                restrictionLevel = RestrictionLevel.LOW.name,
                enrollmentStatus = EnrollmentStatus.ENROLLED.name,
            ),
        )

        // The sweep encrypts account 2's legacy row, but account-scoped reads stay
        // scoped: account 1 sees no children, account 2 sees exactly its own.
        assertEquals(1, migration.migrateLegacyTemplates())
        assertTrue(childRepository.observeChildren(1L).first().isEmpty())
        assertEquals(1, childRepository.observeChildren(2L).first().size)
    }

    @Test
    fun aFullResetClearsBiometricDataAndTheKey() = runBlocking {
        val childId = childRepository.addChild(1L, "Vali", RestrictionLevel.HIGH)
        childRepository.saveFaceEnrollment(1L, childId, plainTemplate)
        val encryptedBeforeReset = db.childProfileDao().observeAll(1L).first().single().faceTemplateRef!!

        val reset = ResetRepositoryImpl(
            db = db,
            settingsStore = SettingsStore(context.settingsDataStore, SessionManager(context)),
            sessionManager = SessionManager(context),
            pinAttemptStore = PinAttemptStore(context),
            keyProvider = keyProvider,
        )
        reset.resetAll()

        assertTrue(db.childProfileDao().observeAll(1L).first().isEmpty())
        // The key is gone too, so the old ciphertext is worthless even if a copy existed.
        assertEquals(TemplateRecovery.Unavailable, cipher.recover(encryptedBeforeReset))
    }

    private companion object {
        const val DB_NAME = "security_phase12_test.db"
    }
}
