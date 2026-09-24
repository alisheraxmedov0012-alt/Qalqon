package uz.faceguard.app.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import uz.faceguard.app.MainActivity
import uz.faceguard.app.R
import uz.faceguard.app.domain.notification.AppNotificationDispatcher
import uz.faceguard.app.domain.notification.AppNotificationEvent
import uz.faceguard.app.domain.notification.DeliveryOutcome
import uz.faceguard.app.domain.notification.NotificationChannelKind
import uz.faceguard.app.domain.notification.NotificationContent
import uz.faceguard.app.domain.notification.NotificationContentFactory
import uz.faceguard.app.domain.notification.NotificationDecision
import uz.faceguard.app.domain.notification.NotificationDestination
import uz.faceguard.app.domain.notification.NotificationType

/**
 * Phase 11 Android notification infrastructure.
 *
 * Everything Android-specific lives here, behind the domain interfaces, so the
 * policy/coordinator stay framework-free and no NotificationManager call is
 * scattered through the app.
 */

/** Intent contract used by notification clicks (no deep-link library needed). */
object NotificationNavigation {
    const val EXTRA_DESTINATION = "uz.faceguard.app.extra.DESTINATION"
    const val EXTRA_REQUEST_ID = "uz.faceguard.app.extra.REQUEST_ID"
    const val DESTINATION_DASHBOARD = "dashboard"
    const val DESTINATION_REQUESTS = "requests"

    fun destinationValue(destination: NotificationDestination): String = when (destination) {
        NotificationDestination.DASHBOARD -> DESTINATION_DASHBOARD
        NotificationDestination.REQUESTS -> DESTINATION_REQUESTS
    }
}

/**
 * Two categories, never one channel per event. Creation is idempotent: an
 * existing channel is left untouched so user settings are never reset.
 */
object NotificationChannels {

    const val PROTECTION_ALERTS = "protection_alerts"
    const val PARENT_REQUESTS = "parent_requests"

    fun idFor(kind: NotificationChannelKind): String = when (kind) {
        NotificationChannelKind.PROTECTION_ALERTS -> PROTECTION_ALERTS
        NotificationChannelKind.PARENT_REQUESTS -> PARENT_REQUESTS
    }

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            if (manager.getNotificationChannel(PROTECTION_ALERTS) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        PROTECTION_ALERTS,
                        context.getString(R.string.notification_channel_protection_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.notification_channel_protection_description)
                        setShowBadge(true)
                    },
                )
            }
            if (manager.getNotificationChannel(PARENT_REQUESTS) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        PARENT_REQUESTS,
                        context.getString(R.string.notification_channel_requests_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = context.getString(R.string.notification_channel_requests_description)
                        setShowBadge(true)
                    },
                )
            }
        }
    }
}

/**
 * Single implementation of the "safe app label" rule used by both notifications
 * and the requests UI: the installed app label when available, otherwise a
 * localized fallback. Never throws, never reads app content.
 */
class AppLabelResolver(private val context: Context) {
    fun labelFor(packageName: String?): String {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return context.getString(R.string.notification_app_unknown)
        return runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrElse { context.getString(R.string.notification_app_unknown) }
    }
}

/** NotificationManagerCompat-backed delivery. */
class AndroidNotificationDispatcher(private val context: Context) : AppNotificationDispatcher {

    override fun ensureChannels() = NotificationChannels.ensure(context)

    override fun areNotificationsEnabled(): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)

    /** Dismisses a previously posted notification (e.g. once a request is resolved). */
    override fun cancel(notificationId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }
    }

    override fun dispatch(decision: NotificationDecision, content: NotificationContent): DeliveryOutcome {
        ensureChannels()
        if (!areNotificationsEnabled()) return DeliveryOutcome.PERMISSION_DENIED
        return try {
            NotificationManagerCompat.from(context).notify(decision.notificationId, build(decision, content))
            DeliveryOutcome.DELIVERED
        } catch (t: SecurityException) {
            DeliveryOutcome.PERMISSION_DENIED
        } catch (t: Throwable) {
            DeliveryOutcome.FAILED
        }
    }

    private fun build(decision: NotificationDecision, content: NotificationContent) =
        NotificationCompat.Builder(context, NotificationChannels.idFor(decision.channel))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setContentIntent(clickIntent(decision))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

    /**
     * Click routing through the existing activity/navigation graph. The account
     * that owns the request is validated by the destination screen when it loads
     * (account-scoped queries), so a stale notification cannot mutate another
     * account's request.
     */
    private fun clickIntent(decision: NotificationDecision): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(NotificationNavigation.EXTRA_DESTINATION, NotificationNavigation.destinationValue(decision.destination))
            decision.relatedRequestId?.let { putExtra(NotificationNavigation.EXTRA_REQUEST_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            decision.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Builds localized notification text; app labels are resolved safely. */
class AndroidNotificationContentFactory(
    private val context: Context,
    private val appLabels: AppLabelResolver = AppLabelResolver(context),
) : NotificationContentFactory {

    override fun contentFor(
        decision: NotificationDecision,
        event: AppNotificationEvent,
    ): NotificationContent = when (decision.type) {
        NotificationType.PROTECTION_BLOCKED -> NotificationContent(
            title = context.getString(R.string.notification_protection_blocked_title),
            body = context.getString(
                R.string.notification_protection_blocked_body,
                appLabels.labelFor((event as? AppNotificationEvent.ProtectionBlocked)?.targetPackageName),
            ),
        )

        NotificationType.PROTECTION_RELEASED -> NotificationContent(
            title = context.getString(R.string.notification_protection_released_title),
            body = context.getString(R.string.notification_protection_released_body),
        )

        NotificationType.PARENT_REQUEST_CREATED -> {
            val request = event as? AppNotificationEvent.ParentRequestCreated
            NotificationContent(
                title = context.getString(R.string.notification_request_created_title),
                body = context.getString(
                    R.string.notification_request_created_body,
                    appLabels.labelFor(request?.targetPackageName),
                    request?.requestedDurationMinutes ?: 0,
                ),
            )
        }
    }

}
