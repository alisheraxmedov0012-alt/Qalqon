package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import uz.faceguard.app.core.security.PinHasher
import uz.faceguard.app.core.util.Validation
import uz.faceguard.app.data.db.UserAccountDao
import uz.faceguard.app.data.db.UserAccountEntity
import uz.faceguard.app.data.prefs.PinAttemptStore
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.security.PinAttemptPolicy
import uz.faceguard.app.domain.security.PinVerification

/**
 * Local account storage.
 *
 * Phase 12 hardening:
 * - the PIN is stored as a PBKDF2-HMAC-SHA256 envelope (per-account random salt),
 *   never plaintext and never reversibly; legacy single-round hashes are still
 *   accepted and are transparently upgraded on the next successful verification;
 * - failed attempts are counted per account in DataStore and start a temporary,
 *   escalating lockout, so brute-forcing the PIN locally is not free. The lockout
 *   is deliberately temporary - a legitimate parent is never permanently locked out;
 * - nothing here logs the PIN or any hash material.
 */
@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val accountDao: UserAccountDao,
    private val sessionManager: SessionManager,
    private val pinAttemptStore: PinAttemptStore,
    private val clock: () -> Long,
) : AccountRepository {

    override val currentAccountId: Flow<Long?> = sessionManager.currentAccountId

    override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult {
        val normalizedPhone = Validation.normalizePhone(phoneNumber)

        // Duplicate phone check - the DAO insert would also throw (unique index),
        // but a pre-check yields a clean typed failure instead of an exception.
        if (accountDao.getByPhone(normalizedPhone) != null) {
            return AuthResult.Failure(AuthResult.Reason.DUPLICATE_PHONE)
        }

        val salt = PinHasher.randomSalt()
        val hash = PinHasher.hash(pin, salt)
        val id = accountDao.insert(
            UserAccountEntity(
                fullName = fullName.trim(),
                phoneNumber = normalizedPhone,
                pinHash = hash,
                pinSalt = salt,
            ),
        )
        sessionManager.setCurrentAccountId(id)
        return AuthResult.Success(
            UserAccount(
                id = id,
                fullName = fullName.trim(),
                phoneNumber = normalizedPhone,
            ),
        )
    }

    override suspend fun login(phoneNumber: String, pin: String): AuthResult {
        val normalizedPhone = Validation.normalizePhone(phoneNumber)
        val entity = accountDao.getByPhone(normalizedPhone)
            ?: return AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)

        // A locked account cannot be probed further, even with the right PIN.
        val attempts = pinAttemptStore.stateFor(entity.id)
        val now = clock()
        if (attempts.isLocked(now)) {
            return AuthResult.Failure(AuthResult.Reason.LOCKED_OUT, attempts.lockedUntilMillis)
        }

        if (!PinHasher.verify(pin, entity.pinHash, entity.pinSalt)) {
            pinAttemptStore.recordFailure(entity.id, now)
            return AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)
        }

        pinAttemptStore.clear(entity.id)
        upgradeLegacyHashIfNeeded(entity, pin)
        sessionManager.setCurrentAccountId(entity.id)
        return AuthResult.Success(entity.toDomain())
    }

    override suspend fun getCurrentAccount(): UserAccount? {
        val id = sessionManager.currentAccountId.first() ?: return null
        return accountDao.getById(id)?.toDomain()
    }

    override suspend fun logout() {
        // Session and its security-sensitive state are both cleared.
        val id = sessionManager.currentAccountId.first()
        if (id != null) pinAttemptStore.clear(id)
        sessionManager.clearSession()
    }

    override suspend fun verifyPin(pin: String): PinVerification {
        val id = sessionManager.currentAccountId.first() ?: return PinVerification.InvalidPin
        val entity = accountDao.getById(id) ?: return PinVerification.InvalidPin

        val attempts = pinAttemptStore.stateFor(id)
        val now = clock()
        if (attempts.isLocked(now)) {
            return PinVerification.LockedOut(attempts.remainingLockMillis(now))
        }

        if (!PinHasher.verify(pin, entity.pinHash, entity.pinSalt)) {
            val next = pinAttemptStore.recordFailure(id, now)
            // A failure that trips the threshold reports the lockout immediately, so
            // the UI can show the remaining wait instead of a generic "wrong PIN".
            return PinAttemptPolicy.outcomeForFailure(next, now)
        }
        pinAttemptStore.clear(id)
        upgradeLegacyHashIfNeeded(entity, pin)
        return PinVerification.Success
    }

    /**
     * Transparent upgrade: after the first successful verification of a legacy hash
     * the account is re-hashed with PBKDF2. The PIN never leaves this call.
     *
     * Failure-isolated: `PinHasher.hash` now rejects a malformed salt rather than
     * zero-filling it, so a (never-expected) failure here must not break a login that
     * has already succeeded — the account simply stays on its legacy hash.
     */
    private suspend fun upgradeLegacyHashIfNeeded(entity: UserAccountEntity, pin: String) {
        if (!PinHasher.needsUpgrade(entity.pinHash)) return
        val salt = PinHasher.randomSalt()
        runCatching { PinHasher.hash(pin, salt) }
            .onSuccess { upgraded -> accountDao.updatePinHash(entity.id, upgraded, salt) }
    }

    private fun UserAccountEntity.toDomain() = UserAccount(
        id = id,
        fullName = fullName,
        phoneNumber = phoneNumber,
        createdAt = createdAt,
    )
}
