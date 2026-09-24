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
    indices = [Index(value = ["at"]), Index(value = ["accountId", "at"])],
)
data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Owning account; the activity log is account-scoped (see MIGRATION_4_5). */
    val accountId: Long,
    /** ActivityEventType.name */
    val type: String,
    val detail: String? = null,
    val at: Long = System.currentTimeMillis(),
)

/**
 * Child-scoped app policy (ALLOW / LIMIT / BLOCK).
 *
 * Added in DB v4 without touching any existing table, so v3 user data survives
 * the upgrade. Column order matches the constructor order because Room builds
 * `CREATE TABLE` from it (see MIGRATION_3_4).
 */
@Entity(
    tableName = "child_app_policies",
    primaryKeys = ["accountId", "childId", "packageName"],
    indices = [Index(value = ["accountId", "childId"])],
)
data class ChildAppPolicyEntity(
    val accountId: Long,
    val childId: Long,
    val packageName: String,
    /** AppPolicyMode.name: ALLOW / LIMIT / BLOCK */
    val mode: String,
    /** ProtectionAction.name */
    val action: String,
    val dailyLimitMinutes: Int? = null,
    val activationDelayMs: Long = 0L,
    val recoveryDelayMs: Long = 0L,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Phase 11: a durable parent request (currently: a child's extra-time request).
 *
 * `requestedDurationMinutes` is the child's ask and `approvedDurationMinutes` the
 * parent's authorization; neither is *usage*. Added in DB v6 without touching any
 * existing table, so v1-v5 user data survives (see MIGRATION_5_6). Column order
 * matches the constructor because Room builds `CREATE TABLE` from it.
 */
@Entity(
    tableName = "parent_requests",
    indices = [
        Index(value = ["accountId", "createdAt"]),
        Index(value = ["accountId", "status", "createdAt"]),
        Index(value = ["accountId", "childId", "status"]),
        Index(value = ["accountId", "deduplicationKey", "status"]),
    ],
)
data class ParentRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val childId: Long,
    val targetPackageName: String,
    val requestType: String,
    val requestedDurationMinutes: Int,
    val approvedDurationMinutes: Int? = null,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long? = null,
    val resolvedAt: Long? = null,
    val resolutionReason: String? = null,
    val source: String,
    val deduplicationKey: String,
)

/**
 * Phase 11: durable notification deduplication + honest delivery status.
 *
 * The primary key *is* the dedup key, and inserts ignore conflicts, so repeated
 * processing of the same event (duplicate emission, retry, process restart)
 * cannot notify twice. Delivery status is stored separately from request state.
 */
@Entity(
    tableName = "notification_records",
    indices = [Index(value = ["accountId", "createdAt"])],
)
data class NotificationRecordEntity(
    @PrimaryKey val deduplicationKey: String,
    val accountId: Long,
    val type: String,
    val relatedRequestId: Long? = null,
    val createdAt: Long,
    val delivered: Boolean = false,
    val deliveryAt: Long? = null,
)
