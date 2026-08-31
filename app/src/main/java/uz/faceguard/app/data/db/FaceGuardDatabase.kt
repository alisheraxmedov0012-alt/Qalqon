package uz.faceguard.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserAccountEntity::class,
        ParentProfileEntity::class,
        ChildProfileEntity::class,
        ProtectedAppEntity::class,
        ActivityEventEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class FaceGuardDatabase : RoomDatabase() {
    abstract fun userAccountDao(): UserAccountDao
    abstract fun parentProfileDao(): ParentProfileDao
    abstract fun childProfileDao(): ChildProfileDao
    abstract fun protectedAppDao(): ProtectedAppDao
    abstract fun activityEventDao(): ActivityEventDao
}
