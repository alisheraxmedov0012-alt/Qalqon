package uz.faceguard.app.legal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.legal.LegalLinks

/**
 * Release Block 2: the public legal/support foundation.
 *
 * Covers the three machine-checkable parts of the block:
 *  - the **centralized URL source** (`LegalLinks`): one definition, no fabricated domain, and
 *    URL construction that is verifiable/overridable without a production domain;
 *  - the **in-app navigation** that links to those resources from the existing Settings
 *    categories and the privacy screen;
 *  - the **deploy-ready pages** under `docs/legal/` that must exist and carry the required
 *    sections, without any invented domain or email.
 *
 * Public hosting itself is NOT verifiable here (no domain/credentials) — see the report.
 */
class Block2LegalFoundationTest {

    private val links by lazy { read("app/src/main/java/uz/faceguard/app/core/legal/LegalLinks.kt") }
    private val opener by lazy { read("app/src/main/java/uz/faceguard/app/core/legal/LegalLinkOpener.kt") }
    private val section by lazy { read("app/src/main/java/uz/faceguard/app/feature/legal/LegalSupportSection.kt") }
    private val settingsScreens by lazy { read("app/src/main/java/uz/faceguard/app/feature/settings/SettingsCategoryScreens.kt") }
    private val privacyScreen by lazy { read("app/src/main/java/uz/faceguard/app/feature/privacy/PrivacyScreen.kt") }
    private val consentScreen by lazy { read("app/src/main/java/uz/faceguard/app/feature/subscription/SubscriptionConsentScreen.kt") }
    private val protectionScreen by lazy { read("app/src/main/java/uz/faceguard/app/feature/protection/ProtectionScreen.kt") }

    // ------------------------------------------------------ centralized source

    @Test
    fun thePathsAreTheStablePublishedPaths() {
        // Cloudflare Pages serves the extensionless English (authoritative) paths.
        assertEquals("/en/privacy-policy", LegalLinks.PATH_PRIVACY_POLICY)
        assertEquals("/en/terms-of-service", LegalLinks.PATH_TERMS_OF_SERVICE)
        assertEquals("/en/delete-account", LegalLinks.PATH_DELETE_ACCOUNT)
        assertEquals("/en/support", LegalLinks.PATH_SUPPORT)
    }

    @Test
    fun theProductionDomainAndSupportEmailAreTheVerifiedOnes() {
        // Release Block 2 finalization: the official domain + real support mailbox.
        assertEquals("https://qalqon.win", LegalLinks.BASE_URL)
        assertEquals("alisheraxmedov0012@gmail.com", LegalLinks.SUPPORT_EMAIL)
        assertEquals("mailto:alisheraxmedov0012@gmail.com", LegalLinks.supportEmailUri())
        assertTrue(LegalLinks.legalSiteConfigured)
        assertTrue(LegalLinks.supportEmailConfigured)

        // The four built URLs are exactly the verified public resources.
        assertEquals("https://qalqon.win/en/privacy-policy", LegalLinks.PRIVACY_POLICY_URL)
        assertEquals("https://qalqon.win/en/terms-of-service", LegalLinks.TERMS_OF_SERVICE_URL)
        assertEquals("https://qalqon.win/en/delete-account", LegalLinks.DELETE_ACCOUNT_URL)
        assertEquals("https://qalqon.win/en/support", LegalLinks.SUPPORT_URL)
    }

    @Test
    fun urlConstructionIsVerifiableAndOverridable() {
        // A configured base yields the exact stable URLs (the override/verification seam).
        val base = "https://example.test"
        assertEquals("https://example.test/en/privacy-policy", LegalLinks.urlFor(LegalLinks.PATH_PRIVACY_POLICY, base))
        assertEquals("https://example.test/en/terms-of-service", LegalLinks.urlFor(LegalLinks.PATH_TERMS_OF_SERVICE, base))
        assertEquals("https://example.test/en/delete-account", LegalLinks.urlFor(LegalLinks.PATH_DELETE_ACCOUNT, base))
        assertEquals("https://example.test/en/support", LegalLinks.urlFor(LegalLinks.PATH_SUPPORT, base))
        // Trailing slashes and surrounding whitespace are normalised.
        assertEquals("https://example.test/privacy", LegalLinks.urlFor("/privacy", "https://example.test/ "))
        assertEquals("https://example.test/privacy", LegalLinks.urlFor("privacy", "https://example.test"))
        // No base => no URL (never a guessed default).
        assertNull(LegalLinks.urlFor("/privacy", null))
        assertNull(LegalLinks.urlFor("/privacy", "   "))
        assertNull(LegalLinks.urlFor("", base))
    }

    @Test
    fun supportEmailBecomesAMailtoUriOnlyWhenConfigured() {
        assertEquals("mailto:support@example.test", LegalLinks.supportEmailUri("support@example.test"))
        assertEquals("mailto:support@example.test", LegalLinks.supportEmailUri("  support@example.test "))
        assertNull(LegalLinks.supportEmailUri(""))
        assertNull(LegalLinks.supportEmailUri("   "))
    }

    @Test
    fun theDomainIsDefinedOnceInTheCentralSource() {
        // The host is declared exactly once, as an assigned string literal (BASE_URL); the
        // path constants are separate, so no other class has to repeat the domain. KDoc
        // mentions of the domain are documentation, not a second definition.
        val assignedUrlLiterals = Regex("""=\s*"https?://[^"]*"""").findAll(links).count()
        assertEquals("exactly one URL literal must be assigned (BASE_URL)", 1, assignedUrlLiterals)
        assertTrue(
            "BASE_URL must hold the only scheme+host",
            links.contains("val BASE_URL: String? = \"https://qalqon.win\""),
        )
    }

    // ------------------------------------------------------- in-app navigation

    @Test
    fun theLegalSectionRendersEveryResourceFromTheCentralSource() {
        listOf(
            "LegalLinks.PRIVACY_POLICY_URL",
            "LegalLinks.TERMS_OF_SERVICE_URL",
            "LegalLinks.DELETE_ACCOUNT_URL",
            "LegalLinks.SUPPORT_URL",
            "LegalLinks.supportEmailUri()",
        ).forEach { ref ->
            assertTrue("the legal section must reference $ref", section.contains(ref))
        }
        // And it must not hardcode any URL.
        assertFalse(section.contains("http://") || section.contains("https://"))
    }

    @Test
    fun thePrivacyAndSupportSettingsCategoriesLinkToTheResources() {
        assertTrue("the Privacy category must show the legal documents", settingsScreens.contains("LegalDocumentsSection("))
        assertTrue("the Support category must show the support channels", settingsScreens.contains("SupportContactSection("))
        assertTrue("the privacy explainer must link the legal documents", privacyScreen.contains("LegalDocumentsSection("))
    }

    @Test
    fun theOpenerReportsAnUnavailableLinkInsteadOfCrashing() {
        assertTrue(opener.contains("ActivityNotFoundException"))
        assertTrue(opener.contains("R.string.legal_link_unavailable"))
        assertTrue(opener.contains("LegalLinks.isConfigured("))
    }

    // ----------------------------------------------------------- the documents

    private val langs = listOf("en", "uz", "ru")
    private val pages = listOf("privacy-policy", "terms-of-service", "delete-account", "support", "index")

    @Test
    fun everyLegalPageExistsInAllThreeLanguages() {
        langs.forEach { lang ->
            pages.forEach { page ->
                val file = File(repoRoot(), "docs/legal/$lang/$page.html")
                assertTrue("missing legal page: ${file.path}", file.isFile)
            }
        }
        assertTrue(File(repoRoot(), "docs/legal/index.html").isFile)
        assertTrue(File(repoRoot(), "docs/legal/README.md").isFile)
    }

    @Test
    fun thePagesCrossLinkToEverySiblingResource() {
        // Each of the four resources must link to the other three, so a reader can reach
        // Privacy <-> Terms <-> Delete Account <-> Support from any page.
        val slugs = listOf("privacy-policy", "terms-of-service", "delete-account", "support")
        langs.forEach { lang ->
            slugs.forEach { slug ->
                val html = read("docs/legal/$lang/$slug.html")
                val links = Regex("""href="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toSet()
                slugs.filter { it != slug }.forEach { sibling ->
                    assertTrue(
                        "$lang/$slug.html must link to $sibling",
                        links.contains("$sibling.html"),
                    )
                }
            }
        }
    }

    @Test
    fun theSupportPageLinksToTheOtherResourcesAndContact() {
        langs.forEach { lang ->
            val support = read("docs/legal/$lang/support.html")
            listOf("privacy-policy.html", "terms-of-service.html", "delete-account.html")
                .forEach { target ->
                    assertTrue("$lang support must link to $target", support.contains("href=\"$target\""))
                }
            assertTrue("$lang support must offer a contact path", support.contains("mailto:alisheraxmedov0012@gmail.com"))
        }
    }

    @Test
    fun thePagesCarryTitleDescriptionAndEffectiveDate() {
        listOf("privacy-policy", "terms-of-service").forEach { page ->
            val html = read("docs/legal/en/$page.html")
            assertTrue("$page must have a <title>", html.contains("<title>"))
            assertTrue("$page must have a meta description", html.contains("name=\"description\""))
            assertTrue("$page must state the effective date", html.contains("Effective date"))
            assertTrue("$page must be versioned", html.contains("v1.0"))
        }
    }

    @Test
    fun thePrivacyPolicyCoversEveryMandatedSection() {
        val policy = read("docs/legal/en/privacy-policy.html").lowercase().replace(Regex("\\s+"), " ")
        listOf(
            "on-device", "no internet permission", "face template", "not written to disk",
            "encrypted", "aes-256-gcm", "android keystore", "retention", "deletion",
            "permissions", "accessibility service", "usage access", "third-party",
            "google play billing", "security", "international", "contact", "children",
        ).forEach { topic ->
            assertTrue("privacy policy must cover '$topic'", policy.contains(topic))
        }
        // Honest non-claims: the policy explicitly refuses the marketing framing.
        assertTrue("policy must disclaim 'military grade'/'100% secure'", policy.contains("we do not describe these as"))
        assertFalse("no 'unbreakable' claim", policy.contains("unbreakable") && !policy.contains("we do not claim this is unbreakable"))
    }

    @Test
    fun theTermsCoverEveryMandatedSection() {
        val terms = read("docs/legal/en/terms-of-service.html").lowercase().replace(Regex("\\s+"), " ")
        listOf(
            "the service", "account", "premium", "free trial", "3-day", "monthly",
            "auto-renew", "cancellation", "refund", "technical limitations", "oem",
            "acceptable use", "account deletion", "liability", "google play", "contact",
        ).forEach { topic ->
            assertTrue("terms must cover '$topic'", terms.contains(topic))
        }
        assertTrue("must explicitly not guarantee 100%", terms.contains("does not guarantee 100% protection"))
        assertFalse("no 'works on every device' claim", terms.contains("works on every device"))
    }

    @Test
    fun theTermsMatchTheBlock1TrialAndBillingDisclosure() {
        val terms = read("docs/legal/en/terms-of-service.html").replace(Regex("\\s+"), " ")
        // Same trial duration and provider as the in-app subscription flow.
        assertTrue(terms.contains("3-day free trial"))
        assertTrue(terms.contains("Google Play"))
        assertTrue(terms.contains("displayed at the time of purchase"))
        // Cancellation is distinguished from account deletion.
        assertTrue(terms.lowercase().contains("not") && terms.lowercase().contains("cancel the subscription"))
    }

    @Test
    fun theDeleteAccountPageIsAnActualDeletionPathNotJustDeactivation() {
        val page = read("docs/legal/en/delete-account.html").lowercase().replace(Regex("\\s+"), " ")
        assertTrue("must describe the in-app deletion", page.contains("settings"))
        assertTrue("must explain the deletion is real", page.contains("real deletion") || page.contains("permanently deletes"))
        assertTrue("must address the subscription separately", page.contains("subscription"))
        // The contact is the real authorized mailbox, and the page must not claim remote deletion.
        assertTrue("must publish the real support address", page.contains("alisheraxmedov0012@gmail.com"))
        assertTrue("must not claim remote deletion", page.contains("we cannot delete it remotely"))
    }

    @Test
    fun everyPagePublishesTheAuthorizedSupportEmailAndNoPlaceholder() {
        // Every published page carries the real mailbox and no leftover token / fake address.
        val email = "alisheraxmedov0012@gmail.com"
        (langs.flatMap { lang -> pages.map { "$lang/$it" } } + listOf("../index")).forEach { rel ->
            val path = if (rel == "../index") "docs/legal/index.html" else "docs/legal/$rel.html"
            val html = read(path)
            assertTrue("$path must publish the authorized support email", html.contains(email))
            assertFalse("$path must not contain the old placeholder token", html.contains("[[SUPPORT_EMAIL]]"))
            assertFalse(
                "$path must not contain a placeholder/example domain",
                html.contains("example.com") || html.contains("official-qalqon-domain") || html.contains("your-real-domain"),
            )
        }
        // The README no longer carries the token either.
        assertFalse(read("docs/legal/README.md").contains("[[SUPPORT_EMAIL]]"))
    }

    @Test
    fun thePagesContainNoAbsoluteUrlSoTheyStayRelocatable() {
        // Navigation between pages stays relative (Cloudflare Pages resolves it); the pages
        // deliberately embed no absolute http(s) URL.
        (langs.flatMap { lang -> pages.map { "$lang/$it" } } + listOf("../index")).forEach { rel ->
            val path = if (rel == "../index") "docs/legal/index.html" else "docs/legal/$rel.html"
            val html = read(path)
            assertFalse("$path must not embed an absolute URL", html.contains("http://") || html.contains("https://"))
        }
    }

    // -------------------------------------------------- Block 1 integration

    @Test
    fun theBlock1ConsentScreenLinksThroughTheCentralSource() {
        assertTrue(consentScreen.contains("LegalLinks.PRIVACY_POLICY_URL"))
        assertTrue(consentScreen.contains("LegalLinks.TERMS_OF_SERVICE_URL"))
        assertTrue(consentScreen.contains("openLegalLink("))
        assertFalse("the consent screen must not hardcode a URL", consentScreen.contains("http://") || consentScreen.contains("https://"))
    }

    @Test
    fun theAccessibilityConsentRemainsItsOwnFlow() {
        // The subscription/legal consent must not swallow the AccessibilityService consent.
        assertTrue(protectionScreen.contains("accessibility_disclosure_title"))
        assertTrue(protectionScreen.contains("accessibility_disclosure_agree"))
        assertFalse(consentScreen.contains("accessibility_disclosure"))
        assertFalse(section.contains("accessibility_disclosure"))
    }

    // ----------------------------------------------------------- localization

    @Test
    fun theLegalUiStringsExistInAllThreeLocales() {
        val uz = read("app/src/main/res/values/strings.xml")
        val en = read("app/src/main/res/values-en/strings.xml")
        val ru = read("app/src/main/res/values-ru/strings.xml")
        val keys = Regex("""name="(legal_[a-z0-9_]+)"""").findAll(uz).map { it.groupValues[1] }.toSet()
        assertTrue("expected the Block 2 legal strings", keys.size >= 10)
        keys.forEach { key ->
            assertTrue("$key missing in en", en.contains("name=\"$key\""))
            assertTrue("$key missing in ru", ru.contains("name=\"$key\""))
        }
    }

    @Test
    fun theDeleteAccountNoteSeparatesDeletionFromSubscriptionCancellation() {
        listOf("values", "values-en", "values-ru").forEach { locale ->
            val strings = read("app/src/main/res/$locale/strings.xml")
            val note = Regex("""name="legal_delete_account_note">([^<]*)<""")
                .find(strings)?.groupValues?.get(1)
            assertNotNull("$locale must define legal_delete_account_note", note)
            assertTrue("$locale note must mention Google Play", note!!.contains("Google Play"))
        }
    }

    // ---------------------------------------------------------------- helpers

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
