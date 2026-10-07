package uz.faceguard.app.feature.subscription

/**
 * The single source of truth for the legal document URLs the subscription consent screen
 * links to (Privacy Policy and Terms of Service).
 *
 * Release Block 1 does **not** fabricate a production URL. Hosting the Privacy Policy,
 * the Terms of Service, account deletion and support is Block 2. Until those public
 * resources exist these are `null`, and the consent screen shows an honest
 * "not available yet" state rather than opening a fake link.
 *
 * Supply the real, stable URLs here once Block 2 publishes them; nothing else has to
 * change.
 */
object LegalLinks {

    /** Block 2 dependency: the hosted Privacy Policy URL, or `null` until published. */
    val PRIVACY_POLICY_URL: String? = null

    /** Block 2 dependency: the hosted Terms of Service URL, or `null` until published. */
    val TERMS_OF_SERVICE_URL: String? = null
}
