package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.data.db.ActivityEventDao
import uz.faceguard.app.data.db.ActivityEventEntity
import uz.faceguard.app.domain.model.ActivityEvent
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.repository.ActivityLogRepository

/** Room-backed, account-scoped local activity log; events never leave the device. */
@Singleton
class ActivityLogRepositoryImpl @Inject constructor(
    private val dao: ActivityEventDao,
) : ActivityLogRepository {

    override fun recent(accountId: Long): Flow<List<ActivityEvent>> =
        dao.observeRecent(accountId).map { list -> list.map { it.toDomain() } }

    override suspend fun log(accountId: Long, type: ActivityEventType, detail: String?) {
        dao.insert(ActivityEventEntity(accountId = accountId, type = type.name, detail = detail))
    }

    override suspend fun clear(accountId: Long) = dao.deleteForAccount(accountId)

    private fun ActivityEventEntity.toDomain() = ActivityEvent(
        id = id,
        accountId = accountId,
        type = runCatching { ActivityEventType.valueOf(type) }
            .getOrDefault(ActivityEventType.UNKNOWN_USER),
        detail = detail,
        at = at,
    )
}
