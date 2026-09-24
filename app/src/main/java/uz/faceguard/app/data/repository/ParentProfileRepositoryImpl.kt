package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.data.db.ParentProfileDao
import uz.faceguard.app.data.db.ParentProfileEntity
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.repository.ParentProfileRepository
import uz.faceguard.app.domain.security.BiometricTemplateCipher
import uz.faceguard.app.domain.security.TemplateRecovery

@Singleton
class ParentProfileRepositoryImpl @Inject constructor(
    private val dao: ParentProfileDao,
    private val templateCipher: BiometricTemplateCipher,
) : ParentProfileRepository {

    override fun observe(accountId: Long): Flow<ParentProfile?> =
        dao.observe(accountId).map { it?.toDomain() }

    /** one profile per account for MVP. */
    override suspend fun createIfMissing(accountId: Long, displayName: String): ParentProfile {
        dao.get(accountId)?.let { return it.toDomain() }
        val id = dao.upsert(
            ParentProfileEntity(accountId = accountId, displayName = displayName.trim()),
        )
        return ParentProfile(id = id, accountId = accountId, displayName = displayName.trim())
    }

    override suspend fun updateDisplayName(accountId: Long, displayName: String) {
        dao.update(accountId, displayName.trim(), System.currentTimeMillis())
    }

    override suspend fun deleteFaceData(accountId: Long) {
        dao.clearFaceData(accountId, System.currentTimeMillis())
    }

    /**
     * Phase 12: the template is encrypted before it touches the database. If the
     * key is unavailable nothing is written, so a plaintext enrollment can never
     * reach Room (fail closed).
     */
    override suspend fun saveFaceEnrollment(accountId: Long, templateRef: String) {
        val protectedRef = templateCipher.protect(templateRef) ?: return
        dao.saveFaceEnrollment(accountId, protectedRef, EnrollmentStatus.ENROLLED.name, System.currentTimeMillis())
    }

    private fun ParentProfileEntity.toDomain() = ParentProfile(
        id = id,
        accountId = accountId,
        displayName = displayName,
        isFaceEnrolled = isFaceEnrolled,
        faceTemplateRef = recoverTemplateRef(faceTemplateRef),
        enrollmentStatus = runCatching { EnrollmentStatus.valueOf(enrollmentStatus) }
            .getOrDefault(EnrollmentStatus.NONE),
        enrollmentVersion = enrollmentVersion,
        lastEnrollmentAt = lastEnrollmentAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /**
     * Decrypts the stored template for transient in-memory use. A payload that
     * cannot be decrypted yields null (no plaintext fallback); legacy plaintext
     * rows stay readable until the encryption sweep moves them.
     */
    private fun recoverTemplateRef(stored: String?): String? = when (val recovery = stored?.let { templateCipher.recover(it) }) {
        is TemplateRecovery.Recovered -> recovery.plainRef
        is TemplateRecovery.LegacyPlaintext -> recovery.plainRef
        else -> null
    }
}
