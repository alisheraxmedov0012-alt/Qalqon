package uz.faceguard.app.feature.help

import java.util.Locale

/**
 * The Qalqon Assistant's intent layer.
 *
 * Why this exists: the assistant used to rank knowledge-base entries by raw token overlap
 * and then let a loose "context" branch — keyed on the ubiquitous word *ilova* / *app* —
 * pre-empt that ranking. Every question that merely mentioned "app" was therefore answered
 * with the same "no protected apps" notice. Routing is now explicit:
 *
 *   QUESTION → INTENT → KNOWLEDGE MATCH → OPTIONAL CONTEXT → ANSWER
 *
 * A question is classified into exactly one [AssistantIntent] from intent-**specific**
 * phrases and keywords. Generic words (`ilova`, `himoya`, `bola`, `qanday`, …) never select
 * an intent on their own, and a runtime-context answer is only considered once the intent is
 * known. This file carries routing vocabulary only — never answer text, which stays in
 * [QalqonKnowledgeBase].
 */
internal enum class AssistantIntent {
    ABOUT_QALQON,
    PROTECTED_APPS,
    PROTECTION_STATUS,
    CHILDREN,
    FACE_ENROLLMENT,
    PIN_BIOMETRIC,
    SCREEN_TIME,
    SCHEDULE,
    EYE_SAFETY,
    NOTIFICATIONS_REQUESTS,
    PRIVACY,
}

/**
 * One routing rule: the [intent] it selects, the knowledge-base [knowledgeId] that answers
 * it, the decisive [phrases] (substring, matched on the normalized question) and [keywords]
 * (token prefixes, so Uzbek agglutination like "ilovalarni" still matches "ilova").
 *
 * A rule is only a candidate when at least one phrase or keyword matches; phrases outweigh
 * keywords, and longer phrases outweigh shorter ones, so the most specific intent wins.
 */
internal data class IntentRule(
    val intent: AssistantIntent,
    val knowledgeId: String,
    val phrases: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
) {
    fun score(normalized: String, tokens: List<String>): Int {
        val phrasePoints = phrases.sumOf { phrase ->
            if (phrase in normalized) PHRASE_BASE + PHRASE_PER_WORD * wordCount(phrase) else 0
        }
        val keywordPoints = keywords.count { keyword -> tokens.any { it.startsWith(keyword) } } * KEYWORD
        return phrasePoints + keywordPoints
    }

    private fun wordCount(phrase: String): Int = phrase.count { it == ' ' } + 1

    companion object {
        const val PHRASE_BASE = 100
        const val PHRASE_PER_WORD = 10
        const val KEYWORD = 20
    }
}

/** The winning rule: which [intent] it is, and which knowledge entry answers it. */
internal data class IntentMatch(val intent: AssistantIntent, val knowledgeId: String, val score: Int)

/**
 * The offline routing lexicon and classifier.
 *
 * Rules are declared in priority order; on an exact score tie the earlier rule wins. Phrases
 * are matched as substrings of a normalized question (lowercased, apostrophes canonicalized,
 * whitespace collapsed), which keeps stop-words such as "nima" usable in phrases like
 * "nima haqida".
 */
internal object QalqonAssistantIntents {

    /** Minimum score for a confident match. One keyword (20) or any phrase (>=110) qualifies. */
    const val MIN_SCORE = 20

    /**
     * Context is applied only to these explicit "state" questions — never merely because a
     * topic word appears. `protectedAppsCount == 0` alone can never select an answer.
     */
    val PROTECTION_OFF_CONTEXT = listOf(
        "ishlamayapti", "ishlamaydi", "ishlamadi", "himoya o'chiq", "himoya o'chirilgan",
        "himoya yoqilmagan", "nega himoya", "himoya yo'q", "protection is off", "protection off",
        "is not working", "doesn't work", "does not work", "isn't working", "disabled",
        "не работает", "выключ", "отключ",
    )
    val NO_APPS_CONTEXT = listOf(
        "himoyalanmagan", "himoyalangan ilova yo'q", "himoyalangan ilovalar yo'q", "birorta ilova",
        "hech narsa himoyalan", "nechta ilova himoyalan", "no apps protected", "no app is protected",
        "no apps are protected", "nothing protected", "no protected apps",
        "ничего не защищ", "нет защищ",
    )
    val NO_CHILDREN_CONTEXT = listOf(
        "bola yo'q", "bolam yo'q", "bola qo'shilmagan", "bola profil yo'q", "farzand yo'q",
        "no child", "no children", "child is not added", "нет ребен",
    )
    val NOTIFICATIONS_OFF_CONTEXT = listOf(
        "kelmayapti", "kelmadi", "o'chirilgan", "yoqilmagan", "not arriving", "not coming",
        "notifications are off", "no notification", "не приход",
    )

    val rules: List<IntentRule> = listOf(
        IntentRule(
            AssistantIntent.PRIVACY, "privacy_data",
            phrases = listOf(
                "yuz ma'lumot", "ma'lumotlar qayerda", "ma'lumot qayerda", "ma'lumotlar saqlan",
                "internetga yubor", "qalqon internet", "internet ishlatadimi", "internet kerakmi",
                "internet bormi", "oflayn ishlaydimi", "offline ishlaydimi", "where is my data",
                "where is the data", "sent to the internet", "use the internet", "need internet",
                "где хранятся данн", "конфиденциальн", "интернет",
            ),
            keywords = listOf("maxfiylik", "privacy", "ma'lumot", "malumot", "internet", "oflayn", "offline", "конфиденц", "данн"),
        ),
        IntentRule(
            AssistantIntent.CHILDREN, "faq_add_child",
            phrases = listOf(
                "bola qo'sh", "bolani qo'sh", "bolani qanday qo'sh", "bola qanday qo'shiladi",
                "bola profil", "bolani qanday yarat", "bolamni qo'sh", "farzand qo'sh",
                "farzandni qo'sh", "farzand qanday qo'sh", "farzand yuzini", "bola yuzini",
                "bolaning yuzini", "add a child", "add child", "child profile", "create a child",
                "добавить ребен", "профиль ребен", "новый ребенок",
            ),
            keywords = listOf("farzand", "farzandni", "bolani", "bolam", "bolamni", "child", "children", "ребен", "ребён"),
        ),
        IntentRule(
            AssistantIntent.FACE_ENROLLMENT, "faq_face",
            phrases = listOf(
                "yuzni ro'yxatdan", "yuzni qayta ro'yxatdan", "yuzni qanday ro'yxatdan",
                "ro'yxatdan o'tkaz", "yuzni o'chir", "yuzni yangila", "face enroll", "enroll a face",
                "register a face", "scan a face", "зарегистрировать лицо",
            ),
            keywords = listOf("ro'yxatdan", "face", "enroll", "enrollment", "лиц"),
        ),
        IntentRule(
            AssistantIntent.PIN_BIOMETRIC, "faq_pin",
            phrases = listOf(
                "pin orqali", "pin kod", "pinni qanday", "pin ni qanday", "pin so'rayapti",
                "biometrik kirish", "barmoq izi", "sign in with the pin", "use pin", "enter your pin",
                "change the pin", "forget my pin", "fingerprint", "biometric", "пароль", "отпечаток",
            ),
            keywords = listOf("pin", "pinni", "biometrik", "biometrika", "barmoq", "parol", "password", "biometric", "fingerprint", "парол", "отпечат"),
        ),
        IntentRule(
            AssistantIntent.SCREEN_TIME, "screentime_limits",
            phrases = listOf(
                "ekran vaqti", "qancha vaqt o'tkaz", "telefonda qancha", "screen time", "экранное время",
            ),
            keywords = listOf("ekran", "screentime", "экран"),
        ),
        IntentRule(
            AssistantIntent.SCHEDULE, "schedule_create",
            phrases = listOf(
                "jadvalni qanday", "jadval qanday", "jadval sozla", "jadval yarat", "jadval qo'sh",
                "qachon himoya", "himoya qachon", "himoya vaqtini", "himoya vaqti",
                "расписание", "расписан",
            ),
            keywords = listOf("jadval", "jadvalni", "schedule", "qachon", "расписан"),
        ),
        IntentRule(
            AssistantIntent.EYE_SAFETY, "eyesafety_configure",
            phrases = listOf(
                "ko'z xavfsizligi", "ko'z tanaffus", "ko'zni himoya", "ko'z salomatligi", "ko'z uchun",
                "eye safety", "eye break", "eye health", "защита глаз", "перерыв для глаз",
            ),
            keywords = listOf("ko'z", "koz", "eye", "tanaffus", "глаз", "перерыв"),
        ),
        IntentRule(
            AssistantIntent.NOTIFICATIONS_REQUESTS, "notifications_requests",
            phrases = listOf(
                "bildirishnoma kelmayapti", "bildirishnoma kelmadi", "bildirishnomani qanday",
                "bildirishnoma yoq", "bildirishnoma sozla", "so'rovlar qayerda", "so'rov qayerda",
                "so'rovni qanday", "notification", "no notification", "notifications are off",
                "requests screen", "уведомления", "нет уведомлений", "запросы",
            ),
            keywords = listOf("bildirishnoma", "bildirishnomani", "so'rov", "sorov", "notification", "request", "уведомл", "запрос"),
        ),
        IntentRule(
            AssistantIntent.PROTECTED_APPS, "apps_protect",
            phrases = listOf(
                "qaysi ilova", "qaysi dastur", "qaysi app", "ilovalarni himoya", "ilovani himoya",
                "ilovani qanday himoya", "ilovani qanday yopaman", "ilovani yop", "himoyalangan ilova",
                "himoyalangan ilovalar", "ilovaga qanday qo'sh", "himoyaga qo'sh", "ilovani qanday blokla",
                "ilovalarni blokla", "birorta ilova", "ilova himoyalanmagan", "protect an app",
                "protect apps", "protected apps", "which app", "block an app", "add an app",
                "защищаемые приложени", "защитить приложени", "заблокировать приложени", "какие приложени",
            ),
            keywords = listOf(
                "himoyalangan", "himoyalash", "himoyalayman", "himoyalashni", "bloklash", "blokla",
                "protect", "protected", "block", "yopaman", "приложен", "защищ", "блокир",
            ),
        ),
        IntentRule(
            AssistantIntent.PROTECTION_STATUS, "protection_turn_on",
            phrases = listOf(
                "himoya nima", "himoya yoqilgan", "himoya yoniq", "himoya holati", "himoya ishlamayapti",
                "himoya ishlamaydi", "himoya ishlamadi", "nega himoya", "himoya o'chiq",
                "himoya o'chirilgan", "himoya yoqilmagan", "himoyani qanday yoq", "himoyani yoq",
                "himoyani yoniq", "protection on", "protection enabled", "protection status",
                "protection off", "protection is off",
                "turn protection on", "enable protection", "why is protection", "protection is not working",
                "protection does not work", "protection isn't working", "защита включ", "защита не работ",
                "защита выключ", "почему защита",
            ),
            keywords = listOf(
                "ishlamayapti", "ishlamaydi", "yoqilgan", "yoqilganmi", "yoniq", "o'chiq",
                "o'chirilgan", "yoqilmagan", "enabled", "disabled", "holati", "включ", "выключ",
            ),
        ),
        IntentRule(
            AssistantIntent.PROTECTION_STATUS, "protection_turn_off",
            phrases = listOf(
                "himoyani o'chir", "himoyani qanday o'chir", "himoya o'chirish", "himoyani o'chirish",
                "himoyani o'chirib", "turn off protection", "disable protection", "turn protection off",
                "how do i turn off protection", "выключить защит", "отключить защит",
            ),
        ),
        IntentRule(
            AssistantIntent.ABOUT_QALQON, "start_about",
            phrases = listOf(
                "nima haqida", "nima uchun kerak", "nima qiladi", "nima vazifa", "vazifasi nima",
                "nima maqsadda", "qalqon haqida", "qalqon nima", "bu qanday ilova", "bu qanday dastur",
                "qanday dastur", "ilova nima", "dastur nima", "about qalqon", "what is qalqon",
                "what is this app", "what is the app", "what does this app", "what does qalqon",
                "что такое qalqon", "что это за приложен", "для чего нужен qalqon", "для чего нужно приложен",
                "что умеет qalqon",
            ),
            keywords = listOf("vazifa", "vazifasi", "tanishuv", "about", "назначение"),
        ),
    )

    /** The best-scoring rule above [MIN_SCORE], or null when the question is not a known intent. */
    fun classify(normalized: String, tokens: List<String>): IntentMatch? =
        rules
            .mapNotNull { rule ->
                val score = rule.score(normalized, tokens)
                if (score >= MIN_SCORE) IntentMatch(rule.intent, rule.knowledgeId, score) else null
            }
            .maxByOrNull { it.score }

    fun matchesAny(normalized: String, phrases: List<String>): Boolean = phrases.any { it in normalized }

    /** Lowercase, canonicalize the various Uzbek apostrophes, collapse whitespace. */
    fun normalize(question: String): String = question
        .lowercase(Locale.ROOT)
        .map { if (it in APOSTROPHES) '\'' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val APOSTROPHES = setOf('\'', '\u2018', '\u2019', '\u02BB', '\u02BC', '\u2032', '`')
}
