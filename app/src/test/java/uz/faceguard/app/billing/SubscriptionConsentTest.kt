package uz.faceguard.app.billing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.billing.BillingProductDetails
import uz.faceguard.app.core.legal.LegalLinks
import uz.faceguard.app.domain.billing.ProductCatalog
import uz.faceguard.app.feature.subscription.SubscriptionConsent

/**
 * Release Block 1: the subscription consent gate and the Important Information / Consent
 * screen.
 *
 * Two halves, both real behavior:
 *  - the **pure gate** ([SubscriptionConsent]) decides whether billing may start — the
 *    purchase must not begin without affirmative consent and a real price;
 *  - the **source contracts** pin that the flow is Premium Offer → Important Information /
 *    Consent → Google Play Billing, that the required disclosures and links are present in
 *    all three locales, that no price or legal URL is fabricated, and that the subscription
 *    consent is not merged with the AccessibilityService consent.
 *
 * Real Google Play purchases are NOT TESTED here (no Play/device) — see the report.
 */
class SubscriptionConsentTest {

    private val consentScreen by lazy {
        read("app/src/main/java/uz/faceguard/app/feature/subscription/SubscriptionConsentScreen.kt")
    }
    private val offerScreen by lazy {
        read("app/src/main/java/uz/faceguard/app/feature/subscription/SubscriptionScreen.kt")
    }
    private val navGraph by lazy { read("app/src/main/java/uz/faceguard/app/navigation/NavGraph.kt") }
    private val protectionScreen by lazy {
        read("app/src/main/java/uz/faceguard/app/feature/protection/ProtectionScreen.kt")
    }
    // Release Block 3 (ACC-03): the accessibility disclosure lives in one shared gate.
    private val accessibilityGate by lazy {
        read("app/src/main/java/uz/faceguard/app/core/ui/qalqon/AccessibilityConsentGate.kt")
    }
    private val uz by lazy { read("app/src/main/res/values/strings.xml") }
    private val en by lazy { read("app/src/main/res/values-en/strings.xml") }
    private val ru by lazy { read("app/src/main/res/values-ru/strings.xml") }

    private fun product(price: String?) = BillingProductDetails(
        productId = ProductCatalog.PRODUCT_ID,
        basePlanId = ProductCatalog.BASE_PLAN_ID,
        offerId = ProductCatalog.TRIAL_OFFER_ID,
        offerToken = "offer-token",
        formattedPrice = price,
        priceCurrencyCode = "USD",
        billingPeriod = "P1M",
        isFreeTrial = true,
        freeTrialPeriod = "P3D",
    )

    // ------------------------------------------------------------------- gate

    @Test
    fun billingCannotStartWithoutAffirmativeConsent() {
        assertFalse(SubscriptionConsent.canStartPurchase(consentGiven = false, product = product("$4.99")))
    }

    @Test
    fun billingCannotStartWithoutAPriceToDisclose() {
        assertFalse(SubscriptionConsent.canStartPurchase(consentGiven = true, product = null))
        assertFalse(SubscriptionConsent.canStartPurchase(consentGiven = true, product = product(null)))
        assertFalse(SubscriptionConsent.canStartPurchase(consentGiven = true, product = product("")))
    }

    @Test
    fun billingStartsOnlyWithConsentAndARealPrice() {
        assertTrue(SubscriptionConsent.canStartPurchase(consentGiven = true, product = product("29 900 UZS")))
    }

    @Test
    fun aBlankPriceIsNeverDisclosable() {
        assertFalse(SubscriptionConsent.hasDisclosablePrice(null))
        assertFalse(SubscriptionConsent.hasDisclosablePrice(product(null)))
        assertFalse(SubscriptionConsent.hasDisclosablePrice(product("   ")))
        assertTrue(SubscriptionConsent.hasDisclosablePrice(product("$4.99")))
    }

    // ---------------------------------------------------- screen + flow contract

    @Test
    fun theConsentScreenExistsAndCarriesEveryRequiredDisclosure() {
        listOf(
            "subscription_consent_title",
            "subscription_trial_badge",
            "subscription_price_after_trial",
            "subscription_billing_frequency",
            "subscription_auto_renew_notice",
            "subscription_consent_trial_conversion",
            "subscription_consent_cancel_how",
            "subscription_benefits_title",
            "subscription_consent_section_limits",
            "subscription_limit_accessibility",
            "subscription_limit_overlay",
            "subscription_limit_system",
            "subscription_limit_recognition",
            "subscription_consent_privacy_policy",
            "subscription_consent_terms_of_service",
            "subscription_consent_agree",
            "subscription_start_trial",
        ).forEach { key ->
            assertTrue("the consent screen must render $key", consentScreen.contains("R.string.$key"))
        }
    }

    @Test
    fun theStartButtonIsGuardedByTheConsentGate() {
        // The purchase is only reached through the gate; there is no unguarded launch.
        assertTrue(consentScreen.contains("SubscriptionConsent.canStartPurchase("))
        assertTrue(consentScreen.contains("if (SubscriptionConsent.canStartPurchase("))
    }

    @Test
    fun theOfferOpensTheConsentScreenAndNeverLaunchesBillingItself() {
        // The Premium Offer navigates to the consent screen...
        assertTrue(navGraph.contains("Routes.SUBSCRIPTION_CONSENT"))
        assertTrue(navGraph.contains("onStartTrial = { navController.navigate(Routes.SUBSCRIPTION_CONSENT) }"))
        // ...and it no longer starts the purchase directly.
        assertFalse(
            "the offer must not launch billing directly",
            offerScreen.contains("viewModel.subscribe("),
        )
        assertTrue(offerScreen.contains("subscription_important_info_hint"))
    }

    @Test
    fun theConsentScreenDefinesNoRawColorOrPriceLiteral() {
        assertFalse("no raw color", consentScreen.contains("Color(0x"))
        assertFalse(
            "no hardcoded price",
            Regex("""[$€£]\s?\d""").containsMatchIn(consentScreen),
        )
    }

    @Test
    fun theLegalLinksAreWiredToTheCentralSourceNotHardcoded() {
        // Block 1 never hardcodes a URL; it resolves the published links from the single
        // LegalLinks source (which Block 2 finalized with the verified production URLs).
        assertFalse("no URL literal in the consent screen", consentScreen.contains("http://"))
        assertFalse("no URL literal in the consent screen", consentScreen.contains("https://"))
        assertEquals("https://qalqon.win/en/privacy-policy", LegalLinks.PRIVACY_POLICY_URL)
        assertEquals("https://qalqon.win/en/terms-of-service", LegalLinks.TERMS_OF_SERVICE_URL)
        assertTrue(
            "the links must be wired to LegalLinks, not hardcoded",
            consentScreen.contains("LegalLinks.PRIVACY_POLICY_URL") &&
                consentScreen.contains("LegalLinks.TERMS_OF_SERVICE_URL"),
        )
    }

    // ------------------------------------- accessibility consent stays separate

    @Test
    fun theSubscriptionConsentDoesNotSwallowTheAccessibilityConsent() {
        // The subscription consent must not be a generic checkbox covering the
        // AccessibilityService disclosure.
        assertFalse(
            "the subscription consent must not reference the accessibility disclosure",
            consentScreen.contains("accessibility_disclosure"),
        )
        // The consent is subscription-specific, not a generic "I agree" covering everything.
        assertTrue(consentScreen.contains("subscription_consent_agree"))
        // The accessibility disclosure remains its own flow (now centralized in the shared
        // gate, ACC-03), rendered by the Protection screen.
        assertTrue(accessibilityGate.contains("accessibility_disclosure_title"))
        assertTrue(accessibilityGate.contains("accessibility_disclosure_agree"))
        assertTrue(protectionScreen.contains("AccessibilityDisclosureDialog("))
    }

    // ------------------------------------------------------------- localization

    @Test
    fun everyNewSubscriptionStringExistsInAllThreeLocales() {
        val keys = Regex("""name="((?:subscription_consent|subscription_limit|subscription_billing_google_play|subscription_important_info_hint)[a-z0-9_]*)"""")
            .findAll(uz).map { it.groupValues[1] }.toSet()
        assertTrue("expected the new Block 1 strings", keys.size >= 20)
        keys.forEach { key ->
            assertTrue("$key missing in en", en.contains("name=\"$key\""))
            assertTrue("$key missing in ru", ru.contains("name=\"$key\""))
        }
    }

    @Test
    fun theThreeLocalesKeepTheSameSubscriptionKeySet() {
        val reference = subscriptionKeys(uz)
        assertEquals(reference, subscriptionKeys(en))
        assertEquals(reference, subscriptionKeys(ru))
    }

    private fun subscriptionKeys(contents: String): Set<String> =
        Regex("""name="(subscription_[a-z_]+)"""").findAll(contents).map { it.groupValues[1] }.toSet()

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
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
