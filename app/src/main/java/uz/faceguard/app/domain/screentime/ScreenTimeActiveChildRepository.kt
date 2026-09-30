package uz.faceguard.app.domain.screentime

import kotlinx.coroutines.flow.Flow

/**
 * Phase 4 Step 1B-7: the account-scoped screen-time target ("active child").
 *
 * This is a **configuration** value: which child this device's screen time is accounted
 * against. It is deliberately *not* face-recognition identity — the runtime's recognised
 * user answers "who is looking right now", which is a different question, and using it
 * here would attribute one child's usage to another the moment recognition changes.
 *
 * Guarantees:
 *  - **account-scoped.** A read or write names its account explicitly, so one account can
 *    never observe or overwrite another's target.
 *  - **no default.** `null` means "no target"; there is no fallback to a first/current
 *    child anywhere in this contract. Callers must handle "nothing selected" rather than
 *    inventing a child.
 *  - **no ownership claim.** Storing an id does not make it valid; resolving whether the
 *    child still exists and belongs to the account is the collector's job (see
 *    [ScreenTimeUsageCollectionCoordinator]).
 *
 * Pure domain: no Android, no DataStore.
 */
interface ScreenTimeActiveChildRepository {

    /** The stored target for [accountId], or `null` when none is set. */
    suspend fun activeChildId(accountId: Long): Long?

    /**
     * The stored target as an observable stream, for screens that must show the current
     * selection without polling. Emits `null` when none is set.
     */
    fun observeActiveChildId(accountId: Long): Flow<Long?>

    suspend fun setActiveChildId(accountId: Long, childId: Long)

    suspend fun clearActiveChildId(accountId: Long)
}
