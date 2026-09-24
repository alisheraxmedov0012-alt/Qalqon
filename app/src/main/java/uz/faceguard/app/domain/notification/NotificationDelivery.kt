package uz.faceguard.app.domain.notification

/**
 * Android delivery is hidden behind these interfaces so no NotificationManager
 * API leaks into the domain layer, and so the policy/coordinator stay
 * unit-testable on the JVM.
 */

/**
 * Localized, ready-to-display content. The Android layer resolves the strings
 * (resources + app labels), so the domain never touches the NotificationManager
 * and no user-visible text is hardcoded in Kotlin.
 */
data class NotificationContent(
    val title: String,
    val body: String,
)

/** Honest delivery result: a request still exists even when delivery is refused. */
enum class DeliveryOutcome {
    DELIVERED,

    /** Runtime POST_NOTIFICATIONS permission not granted (Android 13+). */
    PERMISSION_DENIED,

    /** Notification channels could not be created/queried. */
    CHANNELS_UNAVAILABLE,

    FAILED,
}

/** Android-specific delivery seam. */
interface AppNotificationDispatcher {

    /** Idempotent channel creation; safe to call repeatedly. */
    fun ensureChannels()

    /** True when the OS would actually show our notifications. */
    fun areNotificationsEnabled(): Boolean

    fun dispatch(decision: NotificationDecision, content: NotificationContent): DeliveryOutcome

    /**
     * Dismisses a previously posted notification (e.g. once its request is
     * resolved). Default no-op so non-Android/fake dispatchers stay simple.
     */
    fun cancel(notificationId: Int) = Unit
}

/** Builds the localized content for a decision + its source event. */
fun interface NotificationContentFactory {
    fun contentFor(decision: NotificationDecision, event: AppNotificationEvent): NotificationContent
}

/**
 * Durable notification deduplication + delivery status.
 *
 * [claim] is the dedup gate: it returns true only for a dedup key that has not
 * been recorded before, and records it immediately so repeated processing of the
 * same event (duplicate emission, retry, process restart) cannot notify twice.
 * [markDelivered] stores the *honest* delivery status separately from the request
 * state.
 */
interface NotificationRepository {
    suspend fun claim(decision: NotificationDecision): Boolean

    suspend fun markDelivered(deduplicationKey: String, delivered: Boolean, at: Long)
}

/**
 * Event -> policy -> dedup -> delivery. The single place that turns application
 * events into Android notifications.
 */
class NotificationCoordinator(
    private val policy: NotificationPolicy,
    private val dedup: NotificationRepository,
    private val dispatcher: AppNotificationDispatcher,
    private val contentFactory: NotificationContentFactory,
) {

    /**
     * Returns the delivery outcome, or null when the event produced no
     * notification (suppressed by policy, or already notified).
     *
     * Delivery problems never propagate: the caller's event/request data stays
     * authoritative and notifications must never break protection.
     */
    suspend fun onEvent(event: AppNotificationEvent): DeliveryOutcome? {
        val decision = policy.decide(event) ?: return null
        if (!dedup.claim(decision)) return null

        dispatcher.ensureChannels()
        val outcome = runCatching { dispatcher.dispatch(decision, contentFactory.contentFor(decision, event)) }
            .getOrElse { DeliveryOutcome.FAILED }
        runCatching { dedup.markDelivered(decision.deduplicationKey, outcome == DeliveryOutcome.DELIVERED, decision.at) }
        return outcome
    }
}
