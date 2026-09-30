package uz.faceguard.app.data.repository

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.screentime.UsageAccountingTransaction

/**
 * Phase 4 Step 1B-6: the real transaction boundary for "account the usage *and* advance the
 * checkpoint".
 *
 * Both writes go through DAOs of the same [FaceGuardDatabase], so wrapping them in
 * `withTransaction` makes them one SQLite transaction: either the deltas are persisted and
 * the baselines advance together, or neither happens. That is what removes both failure
 * modes at once — a half-written pair would either re-count the same interval on the next
 * run (duplicate usage) or drop it forever (lost usage).
 *
 * The boundary is expressed at the data layer, where the database actually lives; the
 * domain only sees [UsageAccountingTransaction]. No existing repository or DAO behaviour is
 * changed by this.
 */
@Singleton
class RoomUsageAccountingTransaction @Inject constructor(
    private val database: FaceGuardDatabase,
) : UsageAccountingTransaction {

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}
