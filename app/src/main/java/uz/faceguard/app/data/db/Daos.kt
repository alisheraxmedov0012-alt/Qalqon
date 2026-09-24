package uz.faceguard.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserAccountDao {

    /** ABORT throws IntegrityConstraintViolation on duplicate phone. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: UserAccountEntity): Long

    @Query("SELECT * FROM user_accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UserAccountEntity?

    @Query("SELECT * FROM user_accounts WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getByPhone(phoneNumber: String): UserAccountEntity?

    @Query("DELETE FROM user_accounts")
    suspend fun deleteAll()
}

@Dao
interface ParentProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ParentProfileEntity): Long

    @Query("SELECT * FROM parent_profiles WHERE accountId = :accountId LIMIT 1")
    fun observe(accountId: Long): Flow<ParentProfileEntity?>

    @Query("SELECT * FROM parent_profiles WHERE accountId = :accountId LIMIT 1")
    suspend fun get(accountId: Long): ParentProfileEntity?

    @Query("UPDATE parent_profiles SET displayName = :displayName, updatedAt = :updatedAt WHERE accountId = :accountId")
    suspend fun update(accountId: Long, displayName: String, updatedAt: Long)

    @Query(
        "UPDATE parent_profiles SET isFaceEnrolled = 1, faceTemplateRef = :templateRef, enrollmentStatus = :status, enrollmentVersion = enrollmentVersion + 1, lastEnrollmentAt = :updatedAt, updatedAt = :updatedAt WHERE accountId = :accountId",
    )
    suspend fun saveFaceEnrollment(accountId: Long, templateRef: String, status: String, updatedAt: Long)

    /** Clears face enrollment metadata without touching the profile itself. */
    @Query("UPDATE parent_profiles SET isFaceEnrolled = 0, faceTemplateRef = NULL, enrollmentStatus = 'NONE', enrollmentVersion = enrollmentVersion + 1, lastEnrollmentAt = NULL, updatedAt = :updatedAt WHERE accountId = :accountId")
    suspend fun clearFaceData(accountId: Long, updatedAt: Long)

    @Query("DELETE FROM parent_profiles")
    suspend fun deleteAll()
}

@Dao
interface ChildProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(child: ChildProfileEntity): Long

    @Query("UPDATE child_profiles SET childName = :name, restrictionLevel = :level, updatedAt = :updatedAt WHERE id = :id AND accountId = :accountId")
    suspend fun update(id: Long, accountId: Long, name: String, level: String, updatedAt: Long)

    @Query("DELETE FROM child_profiles WHERE id = :id AND accountId = :accountId")
    suspend fun delete(id: Long, accountId: Long)

    @Query(
        "UPDATE child_profiles SET isFaceEnrolled = 1, faceTemplateRef = :templateRef, enrollmentStatus = :status, enrollmentVersion = enrollmentVersion + 1, lastEnrollmentAt = :updatedAt, updatedAt = :updatedAt WHERE id = :id AND accountId = :accountId",
    )
    suspend fun saveFaceEnrollment(id: Long, accountId: Long, templateRef: String, status: String, updatedAt: Long)

    @Query("SELECT * FROM child_profiles WHERE accountId = :accountId ORDER BY createdAt ASC")
    fun observeAll(accountId: Long): Flow<List<ChildProfileEntity>>

    /** Clears face enrollment metadata without deleting the child profile. */
    @Query("UPDATE child_profiles SET isFaceEnrolled = 0, faceTemplateRef = NULL, enrollmentStatus = 'NONE', enrollmentVersion = enrollmentVersion + 1, lastEnrollmentAt = NULL, updatedAt = :updatedAt WHERE id = :id AND accountId = :accountId")
    suspend fun clearFaceData(id: Long, accountId: Long, updatedAt: Long)

    @Query("DELETE FROM child_profiles")
    suspend fun deleteAll()
}

@Dao
interface ActivityEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ActivityEventEntity): Long

    /** Newest-first events of a single account; the log never leaks across accounts. */
    @Query("SELECT * FROM activity_events WHERE accountId = :accountId ORDER BY at DESC LIMIT 100")
    fun observeRecent(accountId: Long): Flow<List<ActivityEventEntity>>

    @Query("DELETE FROM activity_events WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: Long)

    @Query("DELETE FROM activity_events")
    suspend fun deleteAll()
}

@Dao
interface ProtectedAppDao {
    @Query("SELECT * FROM protected_apps ORDER BY appDisplayName ASC")
    fun observeAll(): Flow<List<ProtectedAppEntity>>

    @Query("SELECT * FROM protected_apps")
    suspend fun getAll(): List<ProtectedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ProtectedAppEntity>)

    @Query("DELETE FROM protected_apps WHERE packageName NOT IN (:packageNames)")
    suspend fun deleteNotIn(packageNames: List<String>)

    @Query("UPDATE protected_apps SET isProtected = :isProtected, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun setProtection(packageName: String, isProtected: Boolean, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM protected_apps WHERE isProtected = 1")
    suspend fun countProtected(): Int

    @Query("SELECT COUNT(*) FROM protected_apps WHERE isProtected = 1")
    fun observeProtectedCount(): Flow<Int>

    @Query("DELETE FROM protected_apps")
    suspend fun deleteAll()
}

/**
 * Child-scoped app policies. Keeps Room entities out of the domain layer —
 * mapping to `AppPolicy` happens in the repository implementation.
 */
@Dao
interface ChildAppPolicyDao {

    @Query(
        "SELECT * FROM child_app_policies WHERE accountId = :accountId AND childId = :childId " +
            "ORDER BY packageName ASC",
    )
    fun observePolicies(accountId: Long, childId: Long): Flow<List<ChildAppPolicyEntity>>

    @Query(
        "SELECT * FROM child_app_policies WHERE accountId = :accountId AND childId = :childId " +
            "AND packageName = :packageName LIMIT 1",
    )
    suspend fun getPolicy(accountId: Long, childId: Long, packageName: String): ChildAppPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: ChildAppPolicyEntity)

    @Query(
        "DELETE FROM child_app_policies WHERE accountId = :accountId AND childId = :childId " +
            "AND packageName = :packageName",
    )
    suspend fun delete(accountId: Long, childId: Long, packageName: String)

    @Query("DELETE FROM child_app_policies WHERE accountId = :accountId AND childId = :childId")
    suspend fun deleteForChild(accountId: Long, childId: Long)

    @Query("DELETE FROM child_app_policies WHERE accountId = :accountId AND packageName = :packageName")
    suspend fun deleteForApp(accountId: Long, packageName: String)

    @Query("DELETE FROM child_app_policies")
    suspend fun deleteAll()
}

/**
 * Phase 11: parent requests. All mutations are ownership-checked and
 * transition-checked in SQL (`WHERE ... status = 'PENDING'`), so concurrent or
 * repeated decisions cannot produce contradictory states.
 */
@Dao
interface ParentRequestDao {

    @Insert
    suspend fun insert(request: ParentRequestEntity): Long

    @Query(
        "SELECT * FROM parent_requests WHERE accountId = :accountId AND status = 'PENDING' " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    fun observePending(accountId: Long, limit: Int): Flow<List<ParentRequestEntity>>

    @Query(
        "SELECT * FROM parent_requests WHERE accountId = :accountId " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    fun observeForAccount(accountId: Long, limit: Int): Flow<List<ParentRequestEntity>>

    @Query("SELECT COUNT(*) FROM parent_requests WHERE accountId = :accountId AND status = 'PENDING'")
    fun observePendingCount(accountId: Long): Flow<Int>

    @Query(
        "SELECT * FROM parent_requests WHERE accountId = :accountId AND childId = :childId " +
            "AND status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit",
    )
    fun observePendingForChild(accountId: Long, childId: Long, limit: Int): Flow<List<ParentRequestEntity>>

    @Query("SELECT * FROM parent_requests WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun byId(accountId: Long, id: Long): ParentRequestEntity?

    @Query(
        "SELECT * FROM parent_requests WHERE accountId = :accountId AND deduplicationKey = :key " +
            "AND status = 'PENDING' ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun activeByKey(accountId: Long, key: String): ParentRequestEntity?

    /**
     * Atomic resolution: only a still-PENDING row of *this account* is updated,
     * so a duplicate/concurrent decision updates zero rows.
     */
    @Query(
        "UPDATE parent_requests SET status = :newStatus, approvedDurationMinutes = :approvedMinutes, " +
            "resolvedAt = :now, updatedAt = :now, resolutionReason = :reason " +
            "WHERE accountId = :accountId AND id = :id AND status = 'PENDING'",
    )
    suspend fun resolve(
        accountId: Long,
        id: Long,
        newStatus: String,
        approvedMinutes: Int?,
        now: Long,
        reason: String?,
    ): Int

    /**
     * Idempotent expiration: only pending rows past their expiry change, and a
     * second call updates nothing.
     */
    @Query(
        "UPDATE parent_requests SET status = 'EXPIRED', resolvedAt = :now, updatedAt = :now, " +
            "resolutionReason = 'expired' " +
            "WHERE accountId = :accountId AND status = 'PENDING' AND expiresAt IS NOT NULL " +
            "AND expiresAt <= :now",
    )
    suspend fun expireStale(accountId: Long, now: Long): Int

    @Query("DELETE FROM parent_requests")
    suspend fun deleteAll()
}

/** Phase 11: durable notification dedup keys + delivery status. */
@Dao
interface NotificationRecordDao {

    /** IGNORE on conflict: an existing dedup key means "already notified". */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: NotificationRecordEntity): Long

    @Query(
        "UPDATE notification_records SET delivered = :delivered, deliveryAt = :at " +
            "WHERE deduplicationKey = :key",
    )
    suspend fun markDelivered(key: String, delivered: Boolean, at: Long)

    @Query("SELECT * FROM notification_records WHERE deduplicationKey = :key LIMIT 1")
    suspend fun byKey(key: String): NotificationRecordEntity?

    @Query("DELETE FROM notification_records")
    suspend fun deleteAll()
}
