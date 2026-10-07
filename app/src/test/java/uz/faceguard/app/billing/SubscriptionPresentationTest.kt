package uz.faceguard.app.billing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.domain.billing.BillingError
import uz.faceguard.app.domain.billing.EntitlementState
import uz.faceguard.app.feature.subscription.SubscriptionPresentation

/**
 * Stage 7: the subscription screen must disclose, on the screen itself, everything
 * Google Play policy requires (trial length, price, billing frequency, auto-renewal,
 * when the first charge happens, cancellation), and every state/error must be localized
 * in all three languages. Purely textual/source contracts, JVM-testable.
 */
class SubscriptionPresentationTest {

    private val screen by lazy { read("app/src/main/java/uz/faceguard/app/feature/subscription/SubscriptionScreen.kt") }
    private val uz by lazy { read("app/src/main/res/values/strings.xml") }
    private val en by lazy { read("app/src/main/res/values-en/strings.xml") }
    private val ru by lazy { read("app/src/main/res/values-ru/strings.xml") }

    @Test
    fun everyEntitlementStateMapsToADistinctNonNullLabel() {
        val labels = EntitlementState.entries.map { SubscriptionPresentation.statusLabelRes(it) }
        assertTrue("all state labels must be non-zero", labels.all { it != 0 })
        assertEquals("state labels must be distinct", labels.size, labels.toSet().size)
    }

    @Test
    fun everyBillingErrorMapsToANonNullMessage() {
        val labels = BillingError.entries.map { SubscriptionPresentation.errorLabelRes(it) }
        assertTrue("all error messages must be non-zero", labels.all { it != 0 })
    }

    @Test
    fun theScreenDisclosesTheRequiredTrialAndBillingTerms() {
        // Each of these string resources must be rendered by the purchase screen itself.
        listOf(
            "subscription_trial_badge",
            "subscription_price_after_trial",
            "subscription_billing_frequency",
            "subscription_auto_renew_notice",
            "subscription_cancel_notice",
            "subscription_manage",
            "subscription_restore",
            "subscription_free_note",
        ).forEach { key ->
            assertTrue("the screen must render $key", screen.contains("R.string.$key"))
        }
    }

    @Test
    fun thePriceIsNeverHardcodedAndComesFromPlay() {
        // The monthly price must come from the Play-provided formatted price.
        assertTrue(screen.contains("product?.formattedPrice"))
        assertTrue(screen.contains("subscription_price_after_trial"))
        // No currency/amount literal in the screen.
        assertTrue(
            "the screen must not hardcode a price",
            !Regex("""[$€£]\s?\d""").containsMatchIn(screen),
        )
    }

    @Test
    fun thePriceIsShownAsPerMonthNotAsATotal() {
        // The disclosure says "Then <price> per month"; the price string must carry /month.
        assertTrue(en.contains("per month"))
        assertTrue(uz.contains("oyiga"))
    }

    @Test
    fun everySubscriptionStringExistsInAllThreeLocales() {
        val keys = Regex("""name="(subscription_[a-z_]+)"""")
            .findAll(uz).map { it.groupValues[1] }.toSet()
        assertTrue("expected subscription strings", keys.size >= 30)
        keys.forEach { key ->
            assertTrue("$key missing in en", en.contains("name=\"$key\""))
            assertTrue("$key missing in ru", ru.contains("name=\"$key\""))
        }
    }

    @Test
    fun theThreeLocalesKeepTheSameSubscriptionKeySet() {
        val uzKeys = subscriptionKeys(uz)
        assertEquals(uzKeys, subscriptionKeys(en))
        assertEquals(uzKeys, subscriptionKeys(ru))
    }

    private fun subscriptionKeys(contents: String): Set<String> =
        Regex("""name="(subscription_[a-z_]+)"""").findAll(contents).map { it.groupValues[1] }.toSet()

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
