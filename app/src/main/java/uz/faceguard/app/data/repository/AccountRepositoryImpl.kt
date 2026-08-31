package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import uz.faceguard.app.core.util.Validation
import uz.faceguard.app.data.db.UserAccountDao
import uz.faceguard.app.data.db.UserAccountEntity
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.domain.model.AuthResult
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.repository.AccountRepository

@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val accountDao: UserAccountDao,
    private val sessionManager: SessionManager,
) : AccountRepository {

    override val currentAccountId: Flow<Long?> = sessionManager.currentAccountId

    override suspend fun register(fullName: String, phoneNumber: String, pin: String): AuthResult {
        val normalizedPhone = Validation.normalizePhone(phoneNumber)

        // Duplicate phone check — DAO insert would also throw (unique index), but
        // a pre-check yields a clean typed failure instead of an exception.
        if (accountDao.getByPhone(normalizedPhone) != null) {
            return AuthResult.Failure(AuthResult.Reason.DUPLICATE_PHONE)
        }

        val salt = SessionManager.randomSalt()
        val id = accountDao.insert(
            UserAccountEntity(
                fullName = fullName.trim(),
                phoneNumber = normalizedPhone,
                pinHash = SessionManager.sha256(salt + pin),
                pinSalt = salt,
            ),
        )
        sessionManager.setCurrentAccountId(id)
        return AuthResult.Success(
            UserAccount(
                id = id,
                fullName = fullName.trim(),
                phoneNumber = normalizedPhone,
                pinHash = SessionManager.sha256(salt + pin),
            ),
        )
    }

    override suspend fun login(phoneNumber: String, pin: String): AuthResult {
        val normalizedPhone = Validation.normalizePhone(phoneNumber)
        val entity = accountDao.getByPhone(normalizedPhone)
            ?: return AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)
        val matches = SessionManager.sha256(entity.pinSalt + pin) == entity.pinHash
        if (!matches) return AuthResult.Failure(AuthResult.Reason.INVALID_CREDENTIALS)
        sessionManager.setCurrentAccountId(entity.id)
        return AuthResult.Success(entity.toDomain())
    }

    override suspend fun getCurrentAccount(): UserAccount? {
        val id = sessionManager.currentAccountId.first() ?: return null
        return accountDao.getById(id)?.toDomain()
    }

    override suspend fun logout() = sessionManager.clearSession()

    override suspend fun verifyPin(pin: String): Boolean {
        val id = sessionManager.currentAccountId.first() ?: return false
        val entity = accountDao.getById(id) ?: return false
        return SessionManager.sha256(entity.pinSalt + pin) == entity.pinHash
    }

    private fun UserAccountEntity.toDomain() = UserAccount(
        id = id,
        fullName = fullName,
        phoneNumber = phoneNumber,
        pinHash = pinHash,
        createdAt = createdAt,
    )
}
