package uz.faceguard.app.billing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.core.billing.BillingProductDetails
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.domain.billing.ProductCatalog
import uz.faceguard.app.feature.subscription.SubscriptionPresentation

/**
 * Release Block 4: the free-trial wording must be **truthful**.
 *
 * The 3-day trial is a Google Play *offer*, and Play enforces trial eligibility
 * server-side. The app must therefore never promise a trial it has not seen: the trial
 * badge and the primary CTA may only say "free trial" when [BillingProductDetails.isFreeTrial]
 * (derived from the preferred offer's first pricing phase) is true. Otherwise the neutral
 * "subscribe" wording is used.
 *
 * Pure JVM + source contracts. Real Google Play offer data is NOT TESTED here.
 */
class Block4TrialDisclosureTest {

    private val offerScreen by lazy {
        read("app/src/main/java/uz/faceguard/app/feature/subscription/SubscriptionScreen.kt")
    }
    private val consentScreen by lazy {
        read("app/src/main/java/uz/faceguard/app/feature/subscription/SubscriptionConsentScreen.kt")
    }
    private val uz by lazy { read("app/src/main/res/values/strings.xml") }
    private val en by lazy { read("app/src/main/res/values-en/strings.xml") }
    private val ru by lazy { read("app/src/main/res/values-ru/strings.xml") }

    private fun product(freeTrial: Boolean) = BillingProductDetails(
        productId = ProductCatalog.PRODUCT_ID,
        basePlanId = ProductCatalog.BASE_PLAN_ID,
        offerId = ProductCatalog.TRIAL_OFFER_ID,
        offerToken = "offer-token",
        formattedPrice = "$4.99",
        priceCurrencyCode = "USD",
        billingPeriod = "P1M",
        isFreeTrial = freeTrial,
        freeTrialPeriod = if (freeTrial) "P3D" else null,
    )

    // --------------------------------------------------------------- pure logic

    @Test
    fun theTrialWordingIsShownOnlyWhenPlayOfferedAFreeTrial() {
        assertTrue(SubscriptionPresentation.showsFreeTrial(product(freeTrial = true)))
        assertFalse(SubscriptionPresentation.showsFreeTrial(product(freeTrial = false)))
    }

    @Test
    fun anUnknownProductNeverClaimsAFreeTrial() {
        // Product not yet loaded / unconfigured: disclose nothing about a trial.
        assertFalse(SubscriptionPresentation.showsFreeTrial(null))
        assertEquals(R.string.subscription_start_subscribe, SubscriptionPresentation.startCtaLabelRes(null))
    }

    @Test
    fun theCtaSaysFreeTrialOnlyForAFreeTrialOffer() {
        assertEquals(
            R.string.subscription_start_trial,
            SubscriptionPresentation.startCtaLabelRes(product(freeTrial = true)),
        )
        assertEquals(
            R.string.subscription_start_subscribe,
            SubscriptionPresentation.startCtaLabelRes(product(freeTrial = false)),
        )
    }

    // ------------------------------------------------------ screen source contract

    @Test
    fun bothPurchaseScreensGateTheTrialWordingOnTheDto() {
        listOf("SubscriptionScreen.kt" to offerScreen, "SubscriptionConsentScreen.kt" to consentScreen)
            .forEach { (name, src) ->
                assertTrue(
                    "$name must consult the trial gate",
                    src.contains("SubscriptionPresentation.showsFreeTrial(product)"),
                )
                assertTrue(
                    "$name must label the CTA from the offer",
                    src.contains("SubscriptionPresentation.startCtaLabelRes(product)"),
                )
            }
    }

    @Test
    fun theLegalPriceAndFrequencyDisclosuresSurviveTheGating() {
        // Gating the *trial* wording must not remove the material subscription terms.
        listOf("SubscriptionScreen.kt" to offerScreen, "SubscriptionConsentScreen.kt" to consentScreen)
            .forEach { (name, src) ->
                assertTrue("$name must still show the price", src.contains("subscription_price_after_trial"))
                assertTrue("$name must still show the billing frequency", src.contains("subscription_billing_frequency"))
                assertTrue("$name must still state auto-renewal", src.contains("subscription_auto_renew_notice"))
            }
    }

    // --------------------------------------------------------------- localization

    @Test
    fun theNeutralCtaExistsInAllThreeLocales() {
        listOf(uz, en, ru).forEach { contents ->
            assertTrue(
                "subscription_start_subscribe missing",
                contents.contains("name=\"subscription_start_subscribe\""),
            )
        }
    }

    @Test
    fun theNeutralCtaDoesNotPromiseATrial() {
        // The neutral labels must not contain the trial wording.
        listOf(uz, en, ru).forEach { contents ->
            val value = Regex("""name="subscription_start_subscribe">([^<]*)<""")
                .find(contents)?.groupValues?.get(1)
                ?: error("subscription_start_subscribe not found")
            assertFalse(value, value.contains("3"))
        }
    }

    // ------------------------------------------------------------------ helpers

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
        error("could not locate the repository root")
    }
}
