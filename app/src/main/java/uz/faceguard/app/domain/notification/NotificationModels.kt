package uz.faceguard.app.domain.notification

/**
 * Phase 11: notification domain.
 *
 * Deliberately separate layers (§6):
 * - [AppNotificationEvent] — an application *domain* event worth telling the parent about.
 * - [NotificationPolicy] — decides whether/how it becomes a notification.
 * - [NotificationDecision] — the resulting, addressable notification.
 * - Notification delivery lives in the Android layer behind [AppNotificationDispatcher].
 *
 * Only sources that genuinely exist today are modelled. `LIMIT_REACHED` is
 * intentionally absent: Phase 4 (screen time) does not exist, so no real limit
 * source could produce it.
 */

enum class NotificationType {
    /** A child was blocked on a protected app (one per block transition). */
    PROTECTION_BLOCKED,

    /** Protection was actually released after the recovery window. */
    PROTECTION_RELEASED,

    /** A child asked for extra time and the parent must decide. */
    PARENT_REQUEST_CREATED,
}

/** Notification categories — never one channel per event. */
enum class NotificationChannelKind {
    PROTECTION_ALERTS,
    PARENT_REQUESTS,
}

/** Where a click should land (existing navigation destinations only). */
enum class NotificationDestination {
    DASHBOARD,
    REQUESTS,
}

/** A meaningful application event that may deserve a parent notification. */
sealed interface AppNotificationEvent {
    val accountId: Long
    val at: Long

    data class ProtectionBlocked(
        override val accountId: Long,
        val childId: Long?,
        val targetPackageName: String?,
        override val at: Long,
    ) : AppNotificationEvent

    data class ProtectionReleased(
        override val accountId: Long,
        override val at: Long,
    ) : AppNotificationEvent

    data class ParentRequestCreated(
        override val accountId: Long,
        val requestId: Long,
        val childId: Long,
        val targetPackageName: String,
        val requestedDurationMinutes: Int,
        override val at: Long,
    ) : AppNotificationEvent
}

/**
 * An addressable notification: stable id, channel, destination and the dedup
 * identity used to prevent repeated processing of the same event.
 */
data class NotificationDecision(
    val type: NotificationType,
    val channel: NotificationChannelKind,
    val destination: NotificationDestination,
    val notificationId: Int,
    val deduplicationKey: String,
    val oneShot: Boolean,
    val relatedRequestId: Long? = null,
    val accountId: Long,
    val at: Long,
)

/**
 * Centralized notification policy. Nothing else in the app decides whether a
 * notification should be raised — the protection engine, runtime, screens and
 * DAOs never build notifications themselves.
 */
fun interface NotificationPolicy {
    /** Null means "do not notify". */
    fun decide(event: AppNotificationEvent): NotificationDecision?
}

/**
 * Default policy.
 *
 * - One notification per block transition / release cycle: the burst of
 *   identical events inside [burstWindowMs] collapses onto one dedup key, while
 *   a later, genuinely new cycle produces a new key (and therefore a new
 *   notification).
 * - One notification per created request, keyed by the stable request id.
 */
class DefaultNotificationPolicy(
    private val burstWindowMs: Long = BURST_WINDOW_MS,
) : NotificationPolicy {

    override fun decide(event: AppNotificationEvent): NotificationDecision? = when (event) {
        is AppNotificationEvent.ProtectionBlocked -> NotificationDecision(
            type = NotificationType.PROTECTION_BLOCKED,
            channel = NotificationChannelKind.PROTECTION_ALERTS,
            destination = NotificationDestination.DASHBOARD,
            notificationId = PROTECTION_BLOCKED_ID,
            deduplicationKey = "blocked:${event.accountId}:${event.childId ?: "-"}:" +
                "${event.targetPackageName?.trim().orEmpty().ifEmpty { "-" }}:${event.at / burstWindowMs}",
            oneShot = true,
            accountId = event.accountId,
            at = event.at,
        )

        is AppNotificationEvent.ProtectionReleased -> NotificationDecision(
            type = NotificationType.PROTECTION_RELEASED,
            channel = NotificationChannelKind.PROTECTION_ALERTS,
            destination = NotificationDestination.DASHBOARD,
            notificationId = PROTECTION_RELEASED_ID,
            deduplicationKey = "released:${event.accountId}:${event.at / burstWindowMs}",
            oneShot = true,
            accountId = event.accountId,
            at = event.at,
        )

        is AppNotificationEvent.ParentRequestCreated -> NotificationDecision(
            type = NotificationType.PARENT_REQUEST_CREATED,
            channel = NotificationChannelKind.PARENT_REQUESTS,
            destination = NotificationDestination.REQUESTS,
            notificationId = requestNotificationId(event.requestId),
            // Stable per request: processing the same creation twice cannot notify twice.
            deduplicationKey = "request:${event.requestId}",
            oneShot = true,
            relatedRequestId = event.requestId,
            accountId = event.accountId,
            at = event.at,
        )
    }

    companion object {
        /** Bursts of the same protection event inside this window collapse to one notification. */
        const val BURST_WINDOW_MS = 60_000L

        const val PROTECTION_BLOCKED_ID = 2001
        const val PROTECTION_RELEASED_ID = 2002
        private const val REQUEST_ID_BASE = 2100
        private const val REQUEST_ID_RANGE = 500

        /** Stable per-request notification id (bounded, so it stays a valid int). */
        fun requestNotificationId(requestId: Long): Int =
            REQUEST_ID_BASE + ((requestId % REQUEST_ID_RANGE).toInt())
    }
}
