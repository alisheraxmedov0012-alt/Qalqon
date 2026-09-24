package uz.faceguard.app.requests

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.notification.AppNotificationDispatcher
import uz.faceguard.app.domain.notification.AppNotificationEvent
import uz.faceguard.app.domain.notification.DefaultNotificationPolicy
import uz.faceguard.app.domain.notification.DeliveryOutcome
import uz.faceguard.app.domain.notification.NotificationChannelKind
import uz.faceguard.app.domain.notification.NotificationContent
import uz.faceguard.app.domain.notification.NotificationContentFactory
import uz.faceguard.app.domain.notification.NotificationCoordinator
import uz.faceguard.app.domain.notification.NotificationDecision
import uz.faceguard.app.domain.notification.NotificationDestination
import uz.faceguard.app.domain.notification.NotificationPolicy
import uz.faceguard.app.domain.notification.NotificationRepository
import uz.faceguard.app.domain.notification.NotificationType

/**
 * Phase 11: the notification policy and the event -> dedup -> delivery
 * coordinator. Pure JVM with hand-written fakes (no mocking of the class under
 * test), and no claim about real device delivery.
 */
class NotificationPolicyTest {

    private val policy: NotificationPolicy = DefaultNotificationPolicy()

    private fun blocked(at: Long = 1_000L, childId: Long? = 5L, pkg: String? = "com.example.youtube") =
        AppNotificationEvent.ProtectionBlocked(accountId = 1L, childId = childId, targetPackageName = pkg, at = at)

    // ---- policy -------------------------------------------------------------

    @Test
    fun onlyGenuineSourcesAreModelled() {
        // LIMIT_REACHED cannot exist without a real limit source (Phase 4).
        assertEquals(3, NotificationType.entries.size)
        assertTrue(NotificationType.entries.none { it.name.contains("LIMIT") })
    }

    @Test
    fun aBlockTransitionProducesOneProtectionNotification() {
        val decision = policy.decide(blocked())!!
        assertEquals(NotificationType.PROTECTION_BLOCKED, decision.type)
        assertEquals(NotificationChannelKind.PROTECTION_ALERTS, decision.channel)
        assertEquals(NotificationDestination.DASHBOARD, decision.destination)
        assertEquals(DefaultNotificationPolicy.PROTECTION_BLOCKED_ID, decision.notificationId)
    }

    @Test
    fun repeatedFramesCollapseOntoOneDeduplicationKey() {
        val first = policy.decide(blocked(at = 1_000L))!!
        // Same event, a few frames later — still the same key (no spam).
        val second = policy.decide(blocked(at = 1_500L))!!
        assertEquals(first.deduplicationKey, second.deduplicationKey)
    }

    @Test
    fun aLaterCycleIsANewNotification() {
        val first = policy.decide(blocked(at = 1_000L))!!
        val later = policy.decide(
            blocked(at = 1_000L + DefaultNotificationPolicy.BURST_WINDOW_MS + 1),
        )!!
        assertTrue("a later cycle must be a new notification", first.deduplicationKey != later.deduplicationKey)
    }

    @Test
    fun differentChildrenOrAppsDoNotShareADeduplicationKey() {
        val base = policy.decide(blocked())!!.deduplicationKey
        assertTrue(base != policy.decide(blocked(childId = 6L))!!.deduplicationKey)
        assertTrue(base != policy.decide(blocked(pkg = "com.example.games"))!!.deduplicationKey)
        assertTrue(base != policy.decide(blocked(at = 1_000L).copy(accountId = 2L))!!.deduplicationKey)
    }

    @Test
    fun aReleaseProducesItsOwnNotification() {
        val decision = policy.decide(AppNotificationEvent.ProtectionReleased(accountId = 1L, at = 1_000L))!!
        assertEquals(NotificationType.PROTECTION_RELEASED, decision.type)
        assertEquals(DefaultNotificationPolicy.PROTECTION_RELEASED_ID, decision.notificationId)
    }

    @Test
    fun aRequestCreationIsStablePerRequestId() {
        val event = AppNotificationEvent.ParentRequestCreated(
            accountId = 1L,
            requestId = 42L,
            childId = 5L,
            targetPackageName = "com.example.youtube",
            requestedDurationMinutes = 15,
            at = 1_000L,
        )
        val first = policy.decide(event)!!
        val again = policy.decide(event.copy(at = 9_999L))!!
        assertEquals(NotificationType.PARENT_REQUEST_CREATED, first.type)
        assertEquals(NotificationChannelKind.PARENT_REQUESTS, first.channel)
        assertEquals(NotificationDestination.REQUESTS, first.destination)
        assertEquals(42L, first.relatedRequestId)
        assertEquals("same request must never notify twice", first.deduplicationKey, again.deduplicationKey)
        assertTrue(first.deduplicationKey != policy.decide(event.copy(requestId = 43L))!!.deduplicationKey)
    }

    @Test
    fun requestNotificationIdsAreStableAndBounded() {
        assertEquals(
            DefaultNotificationPolicy.requestNotificationId(7L),
            DefaultNotificationPolicy.requestNotificationId(7L),
        )
        assertTrue(DefaultNotificationPolicy.requestNotificationId(Long.MAX_VALUE) > 0)
    }

    @Test
    fun thereAreOnlyTwoChannels() {
        assertEquals(2, NotificationChannelKind.entries.size)
    }

    // ---- coordinator --------------------------------------------------------

    private class FakeDedup : NotificationRepository {
        private val claimed = mutableSetOf<String>()
        val deliveries = mutableMapOf<String, Boolean>()
        var claimCount = 0
        override suspend fun claim(decision: NotificationDecision): Boolean {
            claimCount++
            return claimed.add(decision.deduplicationKey)
        }
        override suspend fun markDelivered(deduplicationKey: String, delivered: Boolean, at: Long) {
            deliveries[deduplicationKey] = delivered
        }
    }

    private class FakeDispatcher(
        var outcome: DeliveryOutcome = DeliveryOutcome.DELIVERED,
        var failHard: Boolean = false,
    ) : AppNotificationDispatcher {
        val sentIds = mutableListOf<Int>()
        var channelsEnsured = 0
        override fun ensureChannels() { channelsEnsured++ }
        override fun areNotificationsEnabled(): Boolean = outcome != DeliveryOutcome.PERMISSION_DENIED
        override fun dispatch(decision: NotificationDecision, content: NotificationContent): DeliveryOutcome {
            if (failHard) error("dispatcher exploded")
            sentIds += decision.notificationId
            return outcome
        }
    }

    private fun coordinator(dedup: FakeDedup, dispatcher: FakeDispatcher) = NotificationCoordinator(
        policy = policy,
        dedup = dedup,
        dispatcher = dispatcher,
        contentFactory = NotificationContentFactory { _, _ -> NotificationContent("t", "b") },
    )

    @Test
    fun anEventIsDeliveredOnceAndDuplicatesAreSuppressed() = runBlocking {
        val dedup = FakeDedup()
        val dispatcher = FakeDispatcher()
        val coordinator = coordinator(dedup, dispatcher)
        val event = blocked()

        assertEquals(DeliveryOutcome.DELIVERED, coordinator.onEvent(event))
        assertNull("the same event must not notify twice", coordinator.onEvent(event))
        assertNull(coordinator.onEvent(blocked(at = 1_100L)))

        assertEquals(1, dispatcher.sentIds.size)
        assertEquals(true, dedup.deliveries.values.first())
    }

    @Test
    fun aLaterCycleStillNotifies() = runBlocking {
        val dedup = FakeDedup()
        val dispatcher = FakeDispatcher()
        val coordinator = coordinator(dedup, dispatcher)

        coordinator.onEvent(blocked(at = 1_000L))
        coordinator.onEvent(blocked(at = 1_000L + DefaultNotificationPolicy.BURST_WINDOW_MS + 1))

        assertEquals(2, dispatcher.sentIds.size)
    }

    @Test
    fun permissionDenialIsReportedHonestlyAndNeverCrashes() = runBlocking {
        val dedup = FakeDedup()
        val dispatcher = FakeDispatcher(outcome = DeliveryOutcome.PERMISSION_DENIED)
        val coordinator = coordinator(dedup, dispatcher)

        assertEquals(DeliveryOutcome.PERMISSION_DENIED, coordinator.onEvent(blocked()))
        assertEquals("delivery must be recorded as not delivered", false, dedup.deliveries.values.first())
    }

    @Test
    fun aDispatcherFailureIsContained() = runBlocking {
        val dedup = FakeDedup()
        val dispatcher = FakeDispatcher(failHard = true)
        val coordinator = coordinator(dedup, dispatcher)

        assertEquals(DeliveryOutcome.FAILED, coordinator.onEvent(blocked()))
        assertEquals(false, dedup.deliveries.values.first())
        // The request/event state is untouched by a delivery failure: the dedup
        // record still exists so a retry cannot spam.
        assertNull(coordinator.onEvent(blocked()))
    }

    @Test
    fun channelsAreEnsuredBeforeDelivery() = runBlocking {
        val dispatcher = FakeDispatcher()
        coordinator(FakeDedup(), dispatcher).onEvent(blocked())
        assertEquals(1, dispatcher.channelsEnsured)
    }

    @Test
    fun thePolicyNeverReturnsANotificationForNothing() {
        // Sanity: every modelled event yields a decision (no silent drops), and the
        // type is one of the modelled ones.
        listOf(
            blocked(),
            AppNotificationEvent.ProtectionReleased(1L, 1L),
            AppNotificationEvent.ParentRequestCreated(1L, 1L, 5L, "com.example.youtube", 15, 1L),
        ).forEach { event ->
            val decision = policy.decide(event)
            assertNotNull(decision)
            assertFalse(decision!!.deduplicationKey.isBlank())
        }
    }
}
