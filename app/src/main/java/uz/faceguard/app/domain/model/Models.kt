package uz.faceguard.app.domain.model

/** Core domain models. Framework-free so they are trivially testable. */

data class UserAccount(
    val id: Long = 0,
    val fullName: String,
    val phoneNumber: String,
    val pinHash: String,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class EnrollmentStatus { NONE, ENROLLED, FAILED }

data class ParentProfile(
    val id: Long = 0,
    val accountId: Long,
    val displayName: String,
    val isFaceEnrolled: Boolean = false,
    val faceTemplateRef: String? = null,
    val enrollmentStatus: EnrollmentStatus = EnrollmentStatus.NONE,
    val enrollmentVersion: Int = 0,
    val lastEnrollmentAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class RestrictionLevel { LOW, MEDIUM, HIGH }

data class ChildProfile(
    val id: Long = 0,
    val accountId: Long,
    val childName: String,
    val isFaceEnrolled: Boolean = false,
    val faceTemplateRef: String? = null,
    val restrictionLevel: RestrictionLevel = RestrictionLevel.MEDIUM,
    val enrollmentStatus: EnrollmentStatus = EnrollmentStatus.NONE,
    val enrollmentVersion: Int = 0,
    val lastEnrollmentAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Installed app catalog row with the per-app protection toggle. */
data class ProtectedApp(
    val packageName: String,
    val appDisplayName: String,
    val isProtected: Boolean = false,
)

/** How aggressively event-driven scanning runs. */
enum class ScanMode { BATTERY_SAVER, BALANCED, STRICT }

/** What to do for unknown users / no-face / obstruction fallback. */
enum class BlockPolicy { ALLOW, SOFT_BLOCK, HARD_BLOCK }

/** Activity log event kinds; stored with a timestamp + optional detail. */
enum class ActivityEventType {
    CHILD_RECOGNIZED,
    PARENT_RECOGNIZED,
    UNKNOWN_USER,
    PROTECTED_APP_ENTERED,
    CHILD_BLOCKED,
    PARENT_UNLOCKED,
    EMERGENCY_UNLOCK,
}

data class ActivityEvent(
    val id: Long = 0,
    val type: ActivityEventType,
    val detail: String? = null,
    val at: Long = System.currentTimeMillis(),
)

data class AppSettings(
    val protectionEnabled: Boolean = false,
    val scanMode: ScanMode = ScanMode.BALANCED,
    val recoveryDelayMs: Long = DEFAULT_RECOVERY_DELAY_MS,
    val unknownUserPolicy: BlockPolicy = BlockPolicy.SOFT_BLOCK,
    val noFacePolicy: BlockPolicy = BlockPolicy.ALLOW,
    val lowBatteryBehaviorEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_RECOVERY_DELAY_MS = 30_000L
        val LEVELS_FOR_UI = RestrictionLevel.entries
    }
}
