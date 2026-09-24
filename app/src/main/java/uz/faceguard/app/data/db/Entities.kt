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

/**
 * Phase 4: daily per-app screen-time usage.
 *
 * The composite key (accountId, childId, dateKey, packageName) is the accounting
 * identity: every millisecond belongs to exactly one account + child + local day +
 * package bucket, so usage can never cross accounts, children or days. `usedMs`
 * only changes through SQL-atomic increments and is never negative.
 */
@Entity(
    tableName = "daily_app_usage",
    primaryKeys = ["accountId", "childId", "dateKey", "packageName"],
    indices = [
        Index(value = ["accountId", "childId", "dateKey"]),
        Index(value = ["accountId", "childId", "dateKey", "category"]),
    ],
)
data class DailyAppUsageEntity(
    val accountId: Long,
    val childId: Long,
    /** Local calendar day, `yyyy-MM-dd` (see UsageDateKey). */
    val dateKey: String,
    val packageName: String,
    /** Exact accumulated duration in milliseconds; never negative. */
    val usedMs: Long,
    /** AppCategory.name, resolved once at write time. */
    val category: String,
    val updatedAt: Long,
)

/**
 * Phase 4: child screen-time limits for the scopes not already covered by
 * `child_app_policies.dailyLimitMinutes` (which stays the per-app source).
 *
 * `scope` is LimitScope.name (TOTAL / CATEGORY). `category` holds the
 * AppCategory name for CATEGORY and "" for TOTAL, because SQLite treats NULL
 * primary-key columns as distinct - "" is what actually enforces "at most one
 * TOTAL limit per child".
 */
@Entity(
    tableName = "child_screen_time_limits",
    primaryKeys = ["accountId", "childId", "scope", "category"],
    indices = [Index(value = ["accountId", "childId"])],
)
data class ChildScreenTimeLimitEntity(
    val accountId: Long,
    val childId: Long,
    /** LimitScope.name */
    val scope: String,
    /** AppCategory.name, or "" for TOTAL. */
    val category: String,
    /** null = unlimited, 0 = immediately exceeded, 1..1440 = daily quota (minutes). */
    val limitMinutes: Int?,
    val updatedAt: Long,
)
