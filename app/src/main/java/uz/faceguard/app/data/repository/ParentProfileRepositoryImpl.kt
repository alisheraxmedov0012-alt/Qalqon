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

@Singleton
class ParentProfileRepositoryImpl @Inject constructor(
    private val dao: ParentProfileDao,
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

    override suspend fun setFaceEnrolled(accountId: Long, enrolled: Boolean) {
        // flip the flag; done via upsert of the current row
        dao.get(accountId)?.let { dao.upsert(it.copy(isFaceEnrolled = enrolled, updatedAt = System.currentTimeMillis())) }
    }

    private fun ParentProfileEntity.toDomain() = ParentProfile(
        id = id,
        accountId = accountId,
        displayName = displayName,
        isFaceEnrolled = isFaceEnrolled,
        faceTemplateRef = faceTemplateRef,
        enrollmentStatus = runCatching { EnrollmentStatus.valueOf(enrollmentStatus) }
            .getOrDefault(EnrollmentStatus.NONE),
        enrollmentVersion = enrollmentVersion,
        lastEnrollmentAt = lastEnrollmentAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
