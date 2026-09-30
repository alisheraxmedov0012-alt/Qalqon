package uz.faceguard.app.testing

import uz.faceguard.app.domain.screentime.AppUsageQueryResult
import uz.faceguard.app.domain.screentime.AppUsageSource
import uz.faceguard.app.domain.screentime.UsageAccessState
import uz.faceguard.app.domain.screentime.UsageRange

/**
 * Deterministic [AppUsageSource] for instrumented tests that are not about device usage.
 *
 * Screens probe usage access and report "cannot read usage" separately from "no usage yet", so a
 * test that only cares about, say, limit configuration still needs a source whose capability is
 * known. This one always reports access as granted and never fabricates samples: usage in these
 * tests comes from the real repository, written by the test itself.
 */
internal class FakeAppUsageSource(
    private val access: UsageAccessState = UsageAccessState.AVAILABLE,
) : AppUsageSource {

    override fun usageAccess(): UsageAccessState = access

    override suspend fun queryUsage(range: UsageRange): AppUsageQueryResult =
        AppUsageQueryResult.Available(emptyList())
}
