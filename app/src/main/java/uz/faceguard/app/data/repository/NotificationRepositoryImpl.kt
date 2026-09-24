package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.data.db.NotificationRecordDao
import uz.faceguard.app.data.db.NotificationRecordEntity
import uz.faceguard.app.domain.notification.NotificationDecision
import uz.faceguard.app.domain.notification.NotificationRepository

/**
 * Room-backed notification deduplication.
 *
 * The dedup key is the primary key, so the very first `claim` wins and every
 * later claim of the same key is refused — durable across retries, duplicate
 * collectors and process restarts.
 */
@Singleton
class NotificationRepositoryImpl @Inject constructor(
    private val dao: NotificationRecordDao,
) : NotificationRepository {

    override suspend fun claim(decision: NotificationDecision): Boolean {
        val rowId = dao.insert(
            NotificationRecordEntity(
                deduplicationKey = decision.deduplicationKey,
                accountId = decision.accountId,
                type = decision.type.name,
                relatedRequestId = decision.relatedRequestId,
                createdAt = decision.at,
            ),
        )
        // IGNORE-on-conflict returns -1 when the key already exists.
        return rowId != -1L
    }

    override suspend fun markDelivered(deduplicationKey: String, delivered: Boolean, at: Long) {
        dao.markDelivered(deduplicationKey, delivered, at)
    }
}
