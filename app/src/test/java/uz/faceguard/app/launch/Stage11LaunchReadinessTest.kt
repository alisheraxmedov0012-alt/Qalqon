package uz.faceguard.app.launch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 11: Play Store launch-readiness contracts.
 *
 * These are the *non-duplicative* Stage 11 guards (the target-API, accessibility,
 * offline-manifest and FGS-type checks already live in `PlayComplianceContractTest`).
 * They keep the launch artifacts honest: the privacy policy must stay code-accurate
 * (offline, on-device, no raw-image persistence), the Data safety/channel docs must agree
 * with the manifest, the subscription disclosure must match the real product IDs, and no
 * submission/approval may be claimed without Play Console access.
 */
class Stage11LaunchReadinessTest {

    private fun read(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("missing launch artifact: ${file.path}", file.isFile)
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

    private val policy by lazy { read("docs/playstore/PRIVACY_POLICY.md") }
    private val dataSafety by lazy { read("docs/playstore/DATA_SAFETY.md") }
    private val listing by lazy { read("docs/playstore/STORE_LISTING.md") }
    private val subscription by lazy { read("docs/playstore/SUBSCRIPTION_DISCLOSURE.md") }
    private val deletion by lazy { read("docs/playstore/ACCOUNT_DELETION.md") }
    private val checklist by lazy { read("docs/playstore/PLAY_CONSOLE_CHECKLIST.md") }
    private val manifest by lazy { read("app/src/main/AndroidManifest.xml") }
    private val productCatalog by lazy {
        read("app/src/main/java/uz/faceguard/app/domain/billing/Subscription.kt")
    }

    // ---------------------------------------------------------- doc presence

    @Test
    fun everyRequiredLaunchArtifactExists() {
        listOf(
            "docs/playstore/PRIVACY_POLICY.md",
            "docs/playstore/DATA_SAFETY.md",
            "docs/playstore/STORE_LISTING.md",
            "docs/playstore/PERMISSION_DISCLOSURES.md",
            "docs/playstore/SUBSCRIPTION_DISCLOSURE.md",
            "docs/playstore/ACCOUNT_DELETION.md",
            "docs/playstore/SUPPORT.md",
            "docs/playstore/SCREENSHOTS_PLAN.md",
            "docs/playstore/THIRD_PARTY_LICENSES.md",
            "docs/playstore/PLAY_CONSOLE_CHECKLIST.md",
        ).forEach { read(it) }
    }

    // ------------------------------------------------- privacy policy accuracy

    @Test
    fun thePrivacyPolicyIsCodeAccurateOnTheCorePrivacyClaims() {
        // The policy must state the real behavior, not aspirational behavior.
        assertTrue("policy must state it is offline", policy.contains("offline", ignoreCase = true))
        assertTrue(
            "policy must state it has no Internet permission",
            policy.contains("no Internet", ignoreCase = true) || policy.contains("no Internet permission", ignoreCase = true),
        )
        assertTrue(
            "policy must state raw frames are not written to disk",
            policy.contains("not written to disk", ignoreCase = true) || policy.contains("not saved", ignoreCase = true),
        )
        assertTrue("policy must state templates are encrypted", policy.contains("encrypted", ignoreCase = true))
        assertTrue("policy must describe deletion", policy.contains("delete", ignoreCase = true))
    }

    @Test
    fun thePrivacyPolicyDoesNotClaimAnalyticsOrServersItDoesNotHave() {
        // No analytics/server SDK exists; the policy must not imply one.
        assertTrue(
            "policy must state there is no analytics",
            policy.contains("no advertising, analytics", ignoreCase = true) ||
                policy.contains("no analytics", ignoreCase = true),
        )
    }

    // ------------------------------------------------------- data safety match

    @Test
    fun theDataSafetyClaimMatchesTheOfflineManifest() {
        // "No data collected" is only true because nothing can leave the device.
        assertTrue(
            "Data safety must reference the collect definition",
            dataSafety.contains("transmitting data from your app off a user's device", ignoreCase = true),
        )
        assertTrue(
            "manifest must remove INTERNET for the claim to hold",
            manifest.contains("android.permission.INTERNET\" tools:node=\"remove\""),
        )
        assertTrue(
            "manifest must remove ACCESS_NETWORK_STATE",
            manifest.contains("android.permission.ACCESS_NETWORK_STATE\" tools:node=\"remove\""),
        )
    }

    // ------------------------------------------------------ subscription match

    @Test
    fun theSubscriptionDisclosureMatchesTheRealProductIds() {
        assertTrue(productCatalog.contains("\"qalqon_premium\""))
        assertTrue(productCatalog.contains("\"monthly\""))
        assertTrue(productCatalog.contains("\"trial-3-day\""))
        assertTrue(subscription.contains("qalqon_premium"))
        assertTrue(subscription.contains("monthly"))
        assertTrue(subscription.contains("trial-3-day"))
    }

    @Test
    fun theSubscriptionDisclosureStatesPriceComesFromPlay() {
        assertTrue(
            "price must be described as coming from Google Play, not hardcoded",
            subscription.contains("Play", ignoreCase = true) && subscription.contains("price", ignoreCase = true),
        )
    }

    // ------------------------------------------------------ account deletion

    @Test
    fun theAccountDeletionDocCoversBothInAppAndExternalResource() {
        assertTrue(deletion.contains("in-app", ignoreCase = true))
        assertTrue(deletion.contains("web resource", ignoreCase = true) || deletion.contains("external", ignoreCase = true))
        // And the in-app deletion is real.
        val reset = read("app/src/main/java/uz/faceguard/app/data/repository/ResetRepositoryImpl.kt")
        assertTrue(reset.contains("keyProvider.deleteKey()"))
    }

    // ------------------------------------------------------- store listing rules

    @Test
    fun theStoreListingAvoidsForbiddenClaims() {
        // Scan only the *actual listing copy* (from the English section onward), not the
        // forbidden-claims checklist at the top of the file.
        val marker = "## English (primary)"
        val start = listing.indexOf(marker)
        assertTrue("the listing must contain the English copy section", start >= 0)
        val body = listing.substring(start).lowercase()
        listOf(
            "100% secure",
            "unhackable",
            "unbreakable",
            "impossible to bypass",
            "guaranteed",
            "works on every device",
            "google-approved",
            "coppa compliant",
            "gdpr certified",
        ).forEach { claim ->
            assertTrue("store listing must not claim '$claim'", !body.contains(claim))
        }
    }

    @Test
    fun theStoreListingStatesCoreProtectionIsFree() {
        assertTrue(
            "the free-core-protection promise must be stated",
            listing.contains("free", ignoreCase = true),
        )
    }

    // ---------------------------------------------------------- console honesty

    @Test
    fun theChecklistNeverClaimsSubmissionOrApproval() {
        val lower = checklist.lowercase()
        // The words may appear only in a negated/qualified form; require the explicit
        // "NOT SUBMITTED"/"NOT VERIFIED" framing.
        assertTrue(
            "the checklist must state it is not submitted/verified",
            lower.contains("not submitted") || lower.contains("not verified"),
        )
        assertTrue(
            "the checklist must not claim Play approval",
            !lower.contains("approved by google") && !lower.contains("play approved"),
        )
    }

    // ---------------------------------------------------------- release config

    @Test
    fun theReleaseBundleTaskIsReachableAndReleaseIsMinified() {
        val gradle = read("app/build.gradle.kts")
        // The release build type is minified (Stage 10) and the AAB is produced by
        // `bundleRelease` (verified to build in Stage 11).
        assertTrue(gradle.contains("isMinifyEnabled = true"))
        assertTrue(gradle.contains("isShrinkResources = true"))
    }

    @Test
    fun theVersionIsOverridableForPlayUploads() {
        val gradle = read("app/build.gradle.kts")
        assertTrue(
            "versionCode must be overridable without editing the file",
            gradle.contains("qalqonVersionCode") && gradle.contains("qalqonVersionName"),
        )
        assertTrue(gradle.contains("applicationId = \"uz.faceguard.app\""))
    }
}
