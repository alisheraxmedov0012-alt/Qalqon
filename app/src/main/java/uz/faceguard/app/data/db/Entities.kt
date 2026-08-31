package uz.faceguard.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import uz.faceguard.app.domain.model.EnrollmentStatus

/**
 * Accounts are stored locally only. Normalized digits-only phone is unique.
 * PIN is never stored raw: only salted hash + salt.
 */
@Entity(
    tableName = "user_accounts",
    indices = [Index(value = ["phoneNumber"], unique = true)],
)
data class UserAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fullName: String,
    /** digits-only normalized phone */
    val phoneNumber: String,
    /** SHA-256 over (salt + pin), never the plain PIN. */
    val pinHash: String,
    val pinSalt: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "parent_profiles")
data class ParentProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val displayName: String,
    val isFaceEnrolled: Boolean = false,
    val faceTemplateRef: String? = null,
    val enrollmentStatus: String = EnrollmentStatus.NONE.name,
    val enrollmentVersion: Int = 0,
    val lastEnrollmentAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "child_profiles",
    indices = [Index(value = ["accountId"])],
)
data class ChildProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val childName: String,
    val isFaceEnrolled: Boolean = false,
    val faceTemplateRef: String? = null,
    val restrictionLevel: String,
    val enrollmentStatus: String = EnrollmentStatus.NONE.name,
    val enrollmentVersion: Int = 0,
    val lastEnrollmentAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "protected_apps")
data class ProtectedAppEntity(
    @PrimaryKey val packageName: String,
    val appDisplayName: String,
    val isProtected: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)


@Entity(
    tableName = "activity_events",
    indices = [Index(value = ["at"])],
)
data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ActivityEventType.name */
    val type: String,
    val detail: String? = null,
    val at: Long = System.currentTimeMillis(),
)
