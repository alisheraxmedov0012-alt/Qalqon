package uz.faceguard.app.core.legal

/**
 * The single source of truth for QALQON's public legal and support resources.
 *
 * Every screen (the Premium consent flow, Settings) links through this object, so a
 * public URL is defined once and can never drift between screens. The document text
 * itself lives in the repository under `docs/legal/` and is published on the official
 * domain `https://qalqon.win` (Cloudflare Pages) — see `docs/legal/README.md`.
 *
 * All four resources below were verified live over HTTPS (HTTP 200, valid certificate) in
 * the three supported languages. The app links to the **English (authoritative)** pages;
 * the `/uz/…` and `/ru/…` equivalents are published alongside them.
 *
 * The URL builder still takes the base as a parameter (defaulting to [BASE_URL]) so the
 * construction stays unit-testable and overridable.
 */
object LegalLinks {

    /**
     * Path of the Privacy Policy page, relative to [BASE_URL]. English is the
     * authoritative version published by Cloudflare Pages; the `.html` form is normalised
     * to this extensionless path, which is the canonical URL.
     */
    const val PATH_PRIVACY_POLICY = "/en/privacy-policy"

    /** Path of the Terms of Service page, relative to [BASE_URL]. */
    const val PATH_TERMS_OF_SERVICE = "/en/terms-of-service"

    /** Path of the external account-deletion page, relative to [BASE_URL]. */
    const val PATH_DELETE_ACCOUNT = "/en/delete-account"

    /** Path of the support page, relative to [BASE_URL]. */
    const val PATH_SUPPORT = "/en/support"

    /**
     * The official QALQON production domain. Verified: HTTPS with a valid certificate,
     * `http://` 301-redirects to `https://`.
     */
    val BASE_URL: String? = "https://qalqon.win"

    /**
     * The public support mailbox, published on every legal/support page. This is the
     * project owner's real, monitored address — never replace it with an invented one.
     */
    val SUPPORT_EMAIL: String? = "alisheraxmedov0012@gmail.com"

    /**
     * Builds an absolute URL for [path] on [base], or `null` when no base is configured.
     *
     * Exposed (with the base as a parameter) so tests can verify real URL construction
     * without a production domain.
     */
    fun urlFor(path: String, base: String? = BASE_URL): String? {
        val cleanBase = base?.trim().orEmpty()
        if (cleanBase.isBlank()) return null
        if (path.isBlank()) return null
        return cleanBase.trimEnd('/') + "/" + path.trimStart('/')
    }

    /** The published Privacy Policy URL. */
    val PRIVACY_POLICY_URL: String? get() = urlFor(PATH_PRIVACY_POLICY)

    /** The published Terms of Service URL. */
    val TERMS_OF_SERVICE_URL: String? get() = urlFor(PATH_TERMS_OF_SERVICE)

    /** The published external account-deletion URL. */
    val DELETE_ACCOUNT_URL: String? get() = urlFor(PATH_DELETE_ACCOUNT)

    /** The published support page URL. */
    val SUPPORT_URL: String? get() = urlFor(PATH_SUPPORT)

    /** True when [url] is a usable public link. */
    fun isConfigured(url: String?): Boolean = !url.isNullOrBlank()

    /** A `mailto:` URI for the support mailbox, or `null` when no address is configured. */
    fun supportEmailUri(email: String? = SUPPORT_EMAIL): String? =
        email?.trim()?.takeIf { it.isNotBlank() }?.let { "mailto:$it" }

    /** True when a real support mailbox is configured. */
    val supportEmailConfigured: Boolean get() = isConfigured(SUPPORT_EMAIL)

    /** True when the legal site is hosted (i.e. at least the Privacy Policy URL exists). */
    val legalSiteConfigured: Boolean get() = isConfigured(PRIVACY_POLICY_URL)
}
