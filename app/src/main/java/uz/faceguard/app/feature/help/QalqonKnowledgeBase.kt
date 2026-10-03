package uz.faceguard.app.feature.help

import androidx.annotation.StringRes
import uz.faceguard.app.R

/**
 * QALQON's single authoritative, **local** Help knowledge source.
 *
 * Everything the Help Center and the Qalqon Assistant can say is declared here, once,
 * as string-resource ids. Nothing in this file carries user-facing text: the copy
 * lives in `strings.xml` (uz/en/ru), so it is localized and parity-checked like the
 * rest of the app, and no screen can invent an answer of its own.
 *
 * The content describes only features that exist in this build (protection, children,
 * face enrollment, protected apps, screen time, schedules, eye safety, PIN/biometric
 * access, notifications/requests, privacy, troubleshooting). Nothing here is
 * aspirational.
 */

/** The Help Center's top-level sections. [labelRes] is the localized section title. */
enum class HelpCategory(@StringRes val labelRes: Int) {
    GETTING_STARTED(R.string.help_cat_getting_started),
    PROTECTION(R.string.help_cat_protection),
    CHILDREN(R.string.help_cat_children),
    PROTECTED_APPS(R.string.help_cat_protected_apps),
    SCREEN_TIME(R.string.help_cat_screen_time),
    SCHEDULE(R.string.help_cat_schedule),
    EYE_SAFETY(R.string.help_cat_eye_safety),
    PIN_BIOMETRIC(R.string.help_cat_pin_biometric),
    NOTIFICATIONS_REQUESTS(R.string.help_cat_notifications_requests),
    PRIVACY(R.string.help_cat_privacy),
    TROUBLESHOOTING(R.string.help_cat_troubleshooting),
}

/** Which list an entry came from, so the UI can render and search it uniformly. */
enum class HelpEntryKind { ARTICLE, FAQ, TROUBLESHOOTING }

/** One help article: a title and its steps/`body`, both localized. */
data class HelpArticle(
    val id: String,
    @StringRes val categoryRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

/** One expandable FAQ entry. */
data class HelpFaq(
    val id: String,
    @StringRes val questionRes: Int,
    @StringRes val answerRes: Int,
)

/** One troubleshooting entry: the symptom and what the parent can check. */
data class HelpTroubleshooting(
    val id: String,
    @StringRes val questionRes: Int,
    @StringRes val bodyRes: Int,
)

/**
 * The shape of the knowledge source, so the assistant can be exercised against a
 * small deterministic fixture on the JVM (the real content lives in
 * [QalqonKnowledgeBase]).
 */
interface HelpContent {
    val articles: List<HelpArticle>
    val faq: List<HelpFaq>
    val troubleshooting: List<HelpTroubleshooting>
    val domainTerms: List<String>
}

/**
 * The knowledge base itself. Pure data (res ids only), so it is directly
 * unit-testable and cannot drift from the resource files.
 */
object QalqonKnowledgeBase : HelpContent {

    override val articles: List<HelpArticle> = listOf(
        HelpArticle(
            "start_about",
            R.string.help_cat_getting_started,
            R.string.help_article_about_title,
            R.string.help_article_about_body,
        ),
        HelpArticle(
            "start_first_launch",
            R.string.help_cat_getting_started,
            R.string.help_article_first_launch_title,
            R.string.help_article_first_launch_body,
        ),
        HelpArticle(
            "start_choose_language",
            R.string.help_cat_getting_started,
            R.string.help_article_language_title,
            R.string.help_article_language_body,
        ),
        HelpArticle(
            "protection_turn_on",
            R.string.help_cat_protection,
            R.string.help_article_protection_on_title,
            R.string.help_article_protection_on_body,
        ),
        HelpArticle(
            "protection_turn_off",
            R.string.help_cat_protection,
            R.string.help_article_protection_off_title,
            R.string.help_article_protection_off_body,
        ),
        HelpArticle(
            "children_add",
            R.string.help_cat_children,
            R.string.help_article_add_child_title,
            R.string.help_article_add_child_body,
        ),
        HelpArticle(
            "children_face",
            R.string.help_cat_children,
            R.string.help_article_child_face_title,
            R.string.help_article_child_face_body,
        ),
        HelpArticle(
            "apps_protect",
            R.string.help_cat_protected_apps,
            R.string.help_article_protect_app_title,
            R.string.help_article_protect_app_body,
        ),
        HelpArticle(
            "apps_refresh",
            R.string.help_cat_protected_apps,
            R.string.help_article_refresh_apps_title,
            R.string.help_article_refresh_apps_body,
        ),
        HelpArticle(
            "screentime_limits",
            R.string.help_cat_screen_time,
            R.string.help_article_screen_time_title,
            R.string.help_article_screen_time_body,
        ),
        HelpArticle(
            "schedule_create",
            R.string.help_cat_schedule,
            R.string.help_article_schedule_title,
            R.string.help_article_schedule_body,
        ),
        HelpArticle(
            "eyesafety_configure",
            R.string.help_cat_eye_safety,
            R.string.help_article_eye_safety_title,
            R.string.help_article_eye_safety_body,
        ),
        HelpArticle(
            "pin_biometric",
            R.string.help_cat_pin_biometric,
            R.string.help_article_pin_biometric_title,
            R.string.help_article_pin_biometric_body,
        ),
        HelpArticle(
            "notifications_requests",
            R.string.help_cat_notifications_requests,
            R.string.help_article_requests_title,
            R.string.help_article_requests_body,
        ),
        HelpArticle(
            "privacy_data",
            R.string.help_cat_privacy,
            R.string.help_article_privacy_title,
            R.string.help_article_privacy_body,
        ),
    )

    override val faq: List<HelpFaq> = listOf(
        HelpFaq("faq_offline", R.string.help_faq_offline_q, R.string.help_faq_offline_a),
        HelpFaq("faq_biometric_first", R.string.help_faq_biometric_first_q, R.string.help_faq_biometric_first_a),
        HelpFaq("faq_pin", R.string.help_faq_pin_q, R.string.help_faq_pin_a),
        HelpFaq("faq_face", R.string.help_faq_face_q, R.string.help_faq_face_a),
        HelpFaq("faq_add_child", R.string.help_faq_add_child_q, R.string.help_faq_add_child_a),
        HelpFaq("faq_protect_app", R.string.help_faq_protect_app_q, R.string.help_faq_protect_app_a),
        HelpFaq("faq_protection_on", R.string.help_faq_protection_on_q, R.string.help_faq_protection_on_a),
        HelpFaq("faq_protection_off", R.string.help_faq_protection_off_q, R.string.help_faq_protection_off_a),
        HelpFaq("faq_data_storage", R.string.help_faq_data_storage_q, R.string.help_faq_data_storage_a),
        HelpFaq("faq_refresh_apps", R.string.help_faq_refresh_apps_q, R.string.help_faq_refresh_apps_a),
    )

    override val troubleshooting: List<HelpTroubleshooting> = listOf(
        HelpTroubleshooting("ts_app_missing", R.string.help_ts_app_missing_q, R.string.help_ts_app_missing_a),
        HelpTroubleshooting("ts_child_face", R.string.help_ts_child_face_q, R.string.help_ts_child_face_a),
        HelpTroubleshooting("ts_recognition", R.string.help_ts_recognition_q, R.string.help_ts_recognition_a),
        HelpTroubleshooting("ts_protection_off", R.string.help_ts_protection_off_q, R.string.help_ts_protection_off_a),
        HelpTroubleshooting("ts_app_not_blocked", R.string.help_ts_app_not_blocked_q, R.string.help_ts_app_not_blocked_a),
        HelpTroubleshooting("ts_no_notification", R.string.help_ts_no_notification_q, R.string.help_ts_no_notification_a),
        HelpTroubleshooting("ts_biometric", R.string.help_ts_biometric_q, R.string.help_ts_biometric_a),
        HelpTroubleshooting("ts_pin", R.string.help_ts_pin_q, R.string.help_ts_pin_a),
        HelpTroubleshooting("ts_pin_again", R.string.help_ts_pin_again_q, R.string.help_ts_pin_again_a),
        HelpTroubleshooting("ts_screen_time", R.string.help_ts_screen_time_q, R.string.help_ts_screen_time_a),
    )

    /**
     * The QALQON "domain lexicon": the terms that mark a question as being about
     * QALQON. Matching is deliberately generous (it only routes a question to the
     * local knowledge retrieval); it is **not** a generation model and is never
     * presented as one.
     */
    override val domainTerms: List<String> = listOf(
        // uz
        "qalqon", "himoya", "bola", "farzand", "yuz", "ekran", "vaqt", "jadval",
        "ko'z", "koz", "pin", "biometrik", "bildirishnoma", "so'rov", "sorov",
        "maxfiylik", "ilova", "sozlama", "ro'yxat", "royxat", "parol", "qulf",
        "blok", "dastur", "hisob", "tanaffus", "chegara", "limit",
        // en
        "protection", "protect", "child", "children", "face", "screen", "time",
        "schedule", "eye", "biometric", "notification", "request", "privacy",
        "app", "apps", "setting", "settings", "list", "password", "lock", "block",
        "help", "account", "break", "limit", "enroll", "enrolment", "enrollment",
        "unlock", "pin",
        // ru
        "защит", "ребен", "ребён", "лиц", "экран", "врем", "расписан", "глаз",
        "биометр", "уведомл", "запрос", "конфиденц", "приложен", "настрой",
        "список", "парол", "блокир", "помощ", "аккаунт", "перерыв", "лимит",
        "регистрац", "разблокир",
    )
}
