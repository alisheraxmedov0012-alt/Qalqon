package uz.faceguard.app.billing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.billing.SubscriptionOfferSelection
import uz.faceguard.app.domain.billing.ProductCatalog

/**
 * Release Block 1: which Google Play subscription **offer** the app uses.
 *
 * A base plan can expose several offers, so the price the UI shows and the offer token
 * sent to `BillingFlowParams` must resolve to the same offer. QALQON's catalog carries the
 * 3-day trial offer, so it is preferred by id; "the first offer" is only a fallback.
 *
 * Pure + source-level, JVM-testable. Real Google Play offer data is NOT TESTED here.
 */
class SubscriptionOfferSelectionTest {

    @Test
    fun anEmptyOfferListHasNoSelection() {
        assertEquals(-1, SubscriptionOfferSelection.indexOfPreferredOffer(emptyList()))
    }

    @Test
    fun theTrialOfferIsPreferredOverTheFirstOffer() {
        val ids = listOf("some-other-offer", ProductCatalog.TRIAL_OFFER_ID, "another")
        assertEquals(1, SubscriptionOfferSelection.indexOfPreferredOffer(ids))
    }

    @Test
    fun aSingleOfferIsSelected() {
        assertEquals(0, SubscriptionOfferSelection.indexOfPreferredOffer(listOf(ProductCatalog.TRIAL_OFFER_ID)))
        assertEquals(0, SubscriptionOfferSelection.indexOfPreferredOffer(listOf("only-offer")))
    }

    @Test
    fun withoutATrialTheFirstOfferIsTheFallback() {
        assertEquals(0, SubscriptionOfferSelection.indexOfPreferredOffer(listOf("a", "b")))
    }

    @Test
    fun blankOrNullOfferIdsNeverMatchTheTrial() {
        // A blank/null id never matches the trial, so the real trial offer (index 1) wins.
        assertEquals(1, SubscriptionOfferSelection.indexOfPreferredOffer(listOf(null, ProductCatalog.TRIAL_OFFER_ID)))
        assertEquals(1, SubscriptionOfferSelection.indexOfPreferredOffer(listOf("", ProductCatalog.TRIAL_OFFER_ID)))
        // With no trial present, the first offer is the fallback.
        assertEquals(0, SubscriptionOfferSelection.indexOfPreferredOffer(listOf("", null)))
    }

    @Test
    fun theGatewayAdvertisesAndLaunchesTheSameOfferSelection() {
        // Both the product DTO (what the UI shows) and the billing launch must resolve the
        // offer through the single selector, so the advertised trial cannot diverge from
        // the offer that is actually purchased.
        val gateway = read("app/src/main/java/uz/faceguard/app/core/billing/GooglePlayBillingGateway.kt")
        val uses = Regex("SubscriptionOfferSelection\\.indexOfPreferredOffer\\(").findAll(gateway).count()
        assertTrue("expected the selector to be used for both DTO and launch, was $uses", uses >= 2)
    }

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        assertTrue("missing file: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
