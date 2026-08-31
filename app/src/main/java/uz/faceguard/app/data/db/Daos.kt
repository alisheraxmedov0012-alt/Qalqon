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
        "UPDATE child_profiles SET isFaceEnrolled = :enrolled, enrollmentStatus = :status, enrollmentVersion = enrollmentVersion + 1, lastEnrollmentAt = :updatedAt, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun setFaceEnrolled(id: Long, enrolled: Boolean, status: String, updatedAt: Long)

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

    @Query("SELECT * FROM activity_events ORDER BY at DESC LIMIT 100")
    fun observeRecent(): Flow<List<ActivityEventEntity>>

    @Query("DELETE FROM activity_events")
    suspend fun deleteAll()
}

@Dao
interface ProtectedAppDao {
    @Query("SELECT * FROM protected_apps ORDER BY appDisplayName ASC")
    fun observeAll(): Flow<List<ProtectedAppEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ProtectedAppEntity>)

    @Query("UPDATE protected_apps SET isProtected = :isProtected, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun setProtection(packageName: String, isProtected: Boolean, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM protected_apps WHERE isProtected = 1")
    suspend fun countProtected(): Int

    @Query("SELECT COUNT(*) FROM protected_apps WHERE isProtected = 1")
    fun observeProtectedCount(): Flow<Int>

    @Query("DELETE FROM protected_apps")
    suspend fun deleteAll()
}
