package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.data.db.ChildProfileDao
import uz.faceguard.app.data.db.ChildProfileEntity
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.repository.ChildProfileRepository

@Singleton
class ChildProfileRepositoryImpl @Inject constructor(
    private val dao: ChildProfileDao,
) : ChildProfileRepository {

    override fun observeChildren(accountId: Long): Flow<List<ChildProfile>> =
        dao.observeAll(accountId).map { list -> list.map { it.toDomain() } }

    override suspend fun addChild(accountId: Long, childName: String, level: RestrictionLevel): Long =
        dao.insert(
            ChildProfileEntity(
                accountId = accountId,
                childName = childName.trim(),
                restrictionLevel = level.name,
            ),
        )

    override suspend fun updateChild(accountId: Long, childId: Long, childName: String, level: RestrictionLevel) {
        dao.update(childId, accountId, childName.trim(), level.name, System.currentTimeMillis())
    }

    override suspend fun deleteChild(accountId: Long, childId: Long) =
        dao.delete(childId, accountId)

    override suspend fun deleteFaceData(accountId: Long, childId: Long) {
        dao.clearFaceData(childId, accountId, System.currentTimeMillis())
    }

    override suspend fun setFaceEnrolled(childId: Long, enrolled: Boolean) =
        dao.setFaceEnrolled(childId, enrolled, EnrollmentStatus.ENROLLED.name, System.currentTimeMillis())

    private fun ChildProfileEntity.toDomain() = ChildProfile(
        id = id,
        accountId = accountId,
        childName = childName,
        isFaceEnrolled = isFaceEnrolled,
        faceTemplateRef = faceTemplateRef,
        restrictionLevel = runCatching { RestrictionLevel.valueOf(restrictionLevel) }
            .getOrDefault(RestrictionLevel.MEDIUM),
        enrollmentStatus = runCatching { EnrollmentStatus.valueOf(enrollmentStatus) }
            .getOrDefault(EnrollmentStatus.NONE),
        enrollmentVersion = enrollmentVersion,
        lastEnrollmentAt = lastEnrollmentAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
