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

/** Room-backed local activity log; events never leave the device. */
@Singleton
class ActivityLogRepositoryImpl @Inject constructor(
    private val dao: ActivityEventDao,
) : ActivityLogRepository {

    override val recent: Flow<List<ActivityEvent>> =
        dao.observeRecent().map { list -> list.map { it.toDomain() } }

    override suspend fun log(type: ActivityEventType, detail: String?) {
        dao.insert(ActivityEventEntity(type = type.name, detail = detail))
    }

    override suspend fun clear() = dao.deleteAll()

    private fun ActivityEventEntity.toDomain() = ActivityEvent(
        id = id,
        type = runCatching { ActivityEventType.valueOf(type) }
            .getOrDefault(ActivityEventType.UNKNOWN_USER),
        detail = detail,
        at = at,
    )
}
