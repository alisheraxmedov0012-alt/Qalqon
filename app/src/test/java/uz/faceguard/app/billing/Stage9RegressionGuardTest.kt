package uz.faceguard.app.billing

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.PremiumFeature
import uz.faceguard.app.domain.billing.PremiumAccessEvaluator
import uz.faceguard.app.domain.billing.PremiumEntitlement

/**
 * Stage 9: the monetization trust-boundary and Stage 1–8 regression guard.
 *
 * Stage 9 must not weaken the offline/offline-first contracts it sits on, must not leak a
 * purchase token, and must keep premium gating centralized. Reads the source and asserts
 * the load-bearing invariants, so a regression fails here instead of on a device.
 */
class Stage9RegressionGuardTest {

    private fun read(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("missing file: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root")
    }

    private val root = "app/src/main/java/uz/faceguard/app/"

    // ------------------------------------------------------- security / trust

    @Test
    fun thePurchaseTokenIsNeverLoggedAnywhereInBilling() {
        val sources = listOf(
            "${root}core/billing/SubscriptionManager.kt",
            "${root}core/billing/GooglePlayBillingGateway.kt",
            "${root}core/billing/PurchaseProcessor.kt",
            "${root}core/billing/EntitlementStore.kt",
        ).map { read(it) }

        sources.forEach { source ->
            // Any Log call that could interpolate a token is a leak.
            Regex("Log\\.[a-z]\\([^)]*\\b(token|Token|purchaseToken)\\b")
                .findAll(source)
                .forEach { assertTrue("purchase token must never be logged: ${it.value}", false) }
        }
    }

    @Test
    fun theLocalCacheIsNotTheAuthority_theManagerReVerifiesWithPlay() {
        // The stored value is a state (not a bare boolean), and refresh() re-queries Play
        // rather than trusting the cache.
        val store = read("${root}core/billing/EntitlementStore.kt")
        assertTrue(store.contains("EntitlementState.valueOf("))
        assertTrue(store.contains("PremiumEntitlement("))

        val manager = read("${root}core/billing/SubscriptionManager.kt")
        assertTrue("refresh must query Play", manager.contains("gateway.queryActiveSubscriptions()"))
    }

    @Test
    fun theOfflineFirstManifestContractIsUnchanged() {
        // Billing talks to Play over IPC; it must not have reintroduced INTERNET.
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.INTERNET\" tools:node=\"remove\""))
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE\" tools:node=\"remove\""))
    }

    // ------------------------------------------------------ centralized gating

    @Test
    fun premiumGatingIsCentralizedInTheEvaluator() {
        // Core protection stays free; advanced features require premium. The gate is one
        // pure evaluator, never a scattered boolean.
        assertTrue(PremiumFeature.CORE_PROTECTION.free)
        assertTrue(PremiumAccessEvaluator.isAvailable(PremiumFeature.CORE_PROTECTION, null, 0L))
        assertFalse(
            PremiumAccessEvaluator.isAvailable(
                PremiumFeature.SCHEDULES,
                PremiumEntitlement(state = EntitlementState.EXPIRED),
                0L,
            ),
        )
    }

    // -------------------------------------------------------------- Stage 1–8

    @Test
    fun stage1To3_recognitionLivenessAndMultiFaceAreIntact() {
        assertTrue(read("${root}core/recognition/Recognizer.kt").contains("MultiFacePolicyEngine.decide"))
        assertTrue(read("${root}core/recognition/IdentityDecision.kt").contains("EmbeddingSource.MODEL"))
        assertTrue(read("${root}domain/policy/LivenessPolicy.kt").contains("SPOOF"))
    }

    @Test
    fun stage4And5_enforcementAndBackgroundAreIntact() {
        assertTrue(read("${root}core/protection/ProtectionActionExecutor.kt").contains("fun reassert("))
        assertTrue(read("${root}core/protection/ProtectionServiceLifecycle.kt").contains("ServiceRestartMode.STICKY"))
        assertTrue(read("${root}core/protection/ProtectionServiceLifecycle.kt").contains("CameraRecoveryBackoff"))
    }

    @Test
    fun stage6And7_oemAndVersionCompatibilityAreIntact() {
        assertTrue(read("${root}domain/oem/OemDetector.kt").contains("fun detect("))
        assertTrue(read("${root}core/compat/PlatformCompat.kt").contains("fun foregroundServiceTypes("))
    }

    @Test
    fun stage8_honestReadinessIsIntact() {
        assertTrue(read("${root}domain/protection/ProtectionReadiness.kt").contains("ProtectionReadiness"))
        assertTrue(read("${root}feature/protection/ProtectionScreen.kt").contains("state.readiness"))
    }
}
