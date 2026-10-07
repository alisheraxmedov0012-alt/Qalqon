package uz.faceguard.app.core.legal

/**
 * The single source of truth for QALQON's public legal and support resources.
 *
 * Every screen (the Premium consent flow, Settings) links through this object, so a
 * public URL is defined once and can never drift between screens. The document text
 * itself lives in the repository under `docs/legal/` (deploy-ready) and
 * `docs/playstore/` (working drafts).
 *
 * **No URL is fabricated.** [BASE_URL] and [SUPPORT_EMAIL] are `null` until the official
 * QALQON domain and a real, monitored mailbox exist. While they are `null` every derived
 * URL is `null`, and the UI shows an honest "not available yet" state instead of opening a
 * broken or invented link. Publishing the pages and setting [BASE_URL] is the only change
 * needed to switch the whole app over — see `docs/legal/README.md`.
 *
 * The URL builder takes the base as a parameter (defaulting to [BASE_URL]) so the
 * construction is unit-testable and overridable without shipping a fake production URL.
 */
object LegalLinks {

    /** Path of the Privacy Policy page, relative to [BASE_URL]. */
    const val PATH_PRIVACY_POLICY = "/privacy"

    /** Path of the Terms of Service page, relative to [BASE_URL]. */
    const val PATH_TERMS_OF_SERVICE = "/terms"

    /** Path of the external account-deletion page, relative to [BASE_URL]. */
    const val PATH_DELETE_ACCOUNT = "/delete-account"

    /** Path of the support page, relative to [BASE_URL]. */
    const val PATH_SUPPORT = "/support"

    /**
     * The official QALQON domain (its production host), or `null`.
     *
     * `null` = **public hosting pending** (Release Block 2). Do not set a placeholder or a
     * guessed value; supply the real domain once the legal pages are hosted.
     */
    val BASE_URL: String? = null

    /**
     * The public support mailbox. `null` until a real, monitored address is provided —
     * never invent an email.
     */
    val SUPPORT_EMAIL: String? = null

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

    /** The configured Privacy Policy URL, or `null` while hosting is pending. */
    val PRIVACY_POLICY_URL: String? get() = urlFor(PATH_PRIVACY_POLICY)

    /** The configured Terms of Service URL, or `null` while hosting is pending. */
    val TERMS_OF_SERVICE_URL: String? get() = urlFor(PATH_TERMS_OF_SERVICE)

    /** The configured external account-deletion URL, or `null` while hosting is pending. */
    val DELETE_ACCOUNT_URL: String? get() = urlFor(PATH_DELETE_ACCOUNT)

    /** The configured support page URL, or `null` while hosting is pending. */
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
