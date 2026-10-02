package uz.faceguard.app.help

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.feature.help.AssistantReply
import uz.faceguard.app.feature.help.HelpTextProvider
import uz.faceguard.app.feature.help.LocalQalqonKnowledgeAssistant
import uz.faceguard.app.feature.help.QalqonAssistantContext

/**
 * Regression tests for the assistant's intent routing, exercised against the **real**
 * [uz.faceguard.app.feature.help.QalqonKnowledgeBase] and the **real** localized strings
 * (read from `values`/`values-en`/`values-ru` and mapped to `R.string` ids by name).
 *
 * These are the tests the old suite was missing: it used a three-entry English fixture, so
 * it could not see that a single generic word ("ilova") collapsed every question onto the
 * "no protected apps" context answer.
 */
class QalqonAssistantRoutingTest {

    /** The worst case for the old bug: protection off and nothing configured. */
    private val nothingConfigured = QalqonAssistantContext(
        protectionEnabled = false,
        protectedAppsCount = 0,
        hasChildren = false,
        notificationsEnabled = false,
    )

    // --------------------------------------------------------------- Intent A: about

    @Test
    fun aboutQuestionsRouteToTheAboutArticle() {
        val about = res("help_article_about_title")
        listOf(
            "Bu ilova nima haqida?",
            "Qalqon nima?",
            "Bu ilova nima uchun kerak?",
            "Qalqonning vazifasi nima?",
            "Qalqon qanday dastur?",
            "Bu dastur nima qiladi?",
            "Ilova nima vazifa bajaradi?",
        ).forEach { assertKnowledge("values", it, about, nothingConfigured) }
    }

    // ------------------------------------------------------- Intent B: protected apps

    @Test
    fun protectedAppQuestionsRouteToTheProtectedAppsArticle() {
        val apps = res("help_article_protect_app_title")
        listOf(
            "Qaysi ilovalarni himoya qilish mumkin?",
            "Ilovani qanday himoyalayman?",
            "Qanday ilovalarni bloklash mumkin?",
            "Himoyalangan ilovaga qanday qo‘shaman?",
            "Ilovani himoyaga qanday qo‘shish kerak?",
            "Qaysi ilovalarni bloklash mumkin?",
            "Bolam ishlatmasligi kerak bo‘lgan ilovani qanday yopaman?",
            "Qaysi dasturlarni himoyalash mumkin?",
            "Himoyalangan ilovalar qayerda?",
        ).forEach { assertKnowledge("values", it, apps, nothingConfigured) }
    }

    // --------------------------------------------------------------- Intent C: protection

    @Test
    fun aProtectionOffStateQuestionUsesTheProtectionContext() {
        val reply = ask("values", "Nega himoya ishlamayapti?", nothingConfigured)
        assertTrue("expected a contextual answer, was $reply", reply is AssistantReply.Contextual)
        assertEquals(
            res("help_assistant_ctx_protection_off"),
            (reply as AssistantReply.Contextual).bodyRes,
        )

        // The same question when protection is actually on must not claim it is off.
        val on = nothingConfigured.copy(protectionEnabled = true)
        assertFalse(ask("values", "Nega himoya ishlamayapti?", on) is AssistantReply.Contextual)
    }

    // ------------------------------------------------ cross-cutting regression (the bug)

    @Test
    fun theGenericWordIlovaNoLongerSelectsOneAnswerForEveryQuestion() {
        val about = res("help_article_about_title")
        val apps = res("help_article_protect_app_title")
        assertKnowledge("values", "Bu ilova nima haqida?", about, nothingConfigured)
        assertKnowledge("values", "Bu ilova nima uchun kerak?", about, nothingConfigured)
        assertKnowledge("values", "Ilova nima vazifa bajaradi?", about, nothingConfigured)
        assertKnowledge("values", "Qaysi ilovalarni himoya qilish mumkin?", apps, nothingConfigured)
        assertKnowledge("values", "Ilovani qanday himoyalayman?", apps, nothingConfigured)
    }

    @Test
    fun protectedAppsCountZeroDoesNotHijackUnrelatedIntents() {
        assertKnowledge(
            "values", "Bu ilova nima haqida?", res("help_article_about_title"), nothingConfigured,
        )
        assertKnowledge(
            "values", "Qalqon nima uchun kerak?", res("help_article_about_title"), nothingConfigured,
        )
        assertKnowledge(
            "values", "Biometrik kirish qanday ishlaydi?", res("help_faq_pin_q"), nothingConfigured,
        )
        assertKnowledge(
            "values", "Bolani qanday qo‘shaman?", res("help_faq_add_child_q"), nothingConfigured,
        )
        assertKnowledge(
            "values", "Ekran vaqtini qayerdan ko‘raman?", res("help_article_screen_time_title"),
            nothingConfigured,
        )
    }

    @Test
    fun theThreeReportedQuestionsNoLongerShareOneAnswer() {
        val about = res("help_article_about_title")
        val apps = res("help_article_protect_app_title")
        val noApps = res("help_assistant_ctx_no_apps")

        val first = ask("values", "Bu ilova nima haqida?", nothingConfigured) as AssistantReply.Knowledge
        val second = ask("values", "Bu ilova nima uchun kerak?", nothingConfigured) as AssistantReply.Knowledge
        val third = ask("values", "Qaysi ilovalarni himoya qilish mumkin?", nothingConfigured) as AssistantReply.Knowledge

        assertEquals(about, first.titleRes)
        assertEquals(about, second.titleRes)
        assertEquals(apps, third.titleRes)
        listOf(first, second, third).forEach {
            assertNotEquals("the no-apps notice must not be the fallback", noApps, it.bodyRes)
        }
    }

    // ------------------------------------------------------------- other intents A–J

    @Test
    fun theOtherIntentsRouteToTheirOwnKnowledgeEntries() {
        assertKnowledge("values", "Himoyani qanday yoqaman?", res("help_article_protection_on_title"))
        assertKnowledge("values", "Himoyani qanday o‘chiraman?", res("help_article_protection_off_title"))
        assertKnowledge("values", "Jadvalni qanday sozlayman?", res("help_article_schedule_title"))
        assertKnowledge("values", "Qachon himoya ishlaydi?", res("help_article_schedule_title"))
        assertKnowledge("values", "Ko‘z xavfsizligi nima?", res("help_article_eye_safety_title"))
        assertKnowledge("values", "Ko‘z tanaffusini qanday sozlayman?", res("help_article_eye_safety_title"))
        assertKnowledge("values", "So‘rovlar qayerda?", res("help_article_requests_title"))
        assertKnowledge("values", "Bildirishnomani qanday yoqaman?", res("help_article_requests_title"))
        assertKnowledge("values", "Yuz ma‘lumotlari qayerda saqlanadi?", res("help_article_privacy_title"))
        assertKnowledge("values", "Ma‘lumotlar internetga yuboriladimi?", res("help_article_privacy_title"))
        assertKnowledge("values", "PINni qanday o‘zgartiraman?", res("help_faq_pin_q"))
        assertKnowledge("values", "Yuzni qanday ro‘yxatdan o‘tkazaman?", res("help_faq_face_q"))
        assertKnowledge(
            "values", "Farzand yuzini qanday ro‘yxatdan o‘tkazaman?", res("help_faq_add_child_q"),
        )
    }

    // ------------------------------------------------------------- Intent K: refusal

    @Test
    fun outOfDomainQuestionsAreRefused() {
        listOf(
            "Bugun Toshkentda ob-havo qanday?",
            "Python kod yoz",
            "2+2 nechchi?",
            "Bitcoin narxi qancha?",
            "Rus tiliga tarjima",
        ).forEach { question ->
            assertEquals(
                "'$question' must be refused",
                AssistantReply.OutOfDomain,
                ask("values", question, nothingConfigured),
            )
        }
    }

    @Test
    fun anInDomainQuestionTheGuideDoesNotCoverIsReportedHonestly() {
        assertEquals(
            AssistantReply.NotFound,
            ask("values", "How do I cancel my account?", nothingConfigured),
        )
    }

    // ------------------------------------------------------------ multilingual routing

    @Test
    fun routingAlsoWorksInEnglishAndRussian() {
        assertKnowledge("values-en", "What is Qalqon?", res("help_article_about_title"))
        assertKnowledge("values-en", "Which apps can I protect?", res("help_article_protect_app_title"))
        assertKnowledge("values-en", "How do I add a child?", res("help_faq_add_child_q"))
        assertKnowledge("values-en", "How do I change the pin?", res("help_faq_pin_q"))

        assertKnowledge("values-ru", "Что такое Qalqon?", res("help_article_about_title"))
        assertKnowledge("values-ru", "Какие приложения можно защитить?", res("help_article_protect_app_title"))
        assertKnowledge("values-ru", "Как добавить ребёнка?", res("help_faq_add_child_q"))
    }

    // ------------------------------------------------------------------ helpers

    private fun assertKnowledge(
        locale: String,
        question: String,
        expectedTitle: Int,
        context: QalqonAssistantContext? = null,
    ) {
        val reply = ask(locale, question, context)
        assertTrue(
            "'$question' ($locale) should be answered from the knowledge base, was $reply",
            reply is AssistantReply.Knowledge,
        )
        reply as AssistantReply.Knowledge
        assertEquals(
            "'$question' ($locale) routed to the wrong entry (bodyRes=${reply.bodyRes})",
            expectedTitle,
            reply.titleRes,
        )
        assertNotEquals(
            "'$question' ($locale) must not fall back to the no-apps context",
            res("help_assistant_ctx_no_apps"),
            reply.bodyRes,
        )
    }

    private fun ask(locale: String, question: String, context: QalqonAssistantContext? = null): AssistantReply {
        val assistant = LocalQalqonKnowledgeAssistant(provider(locale))
        return runBlocking { assistant.ask(question, context) }
    }

    /** Resolves `R.string.<name>` by reflection; locale only selects the text source. */
    private fun res(name: String): Int = R.string::class.java.getField(name).getInt(null)

    private fun provider(locale: String): HelpTextProvider {
        val xml = File(repoRoot(), "app/src/main/res/$locale/strings.xml").readText()
        val byName = Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to decode(it.groupValues[2]) }
        val idToText = HashMap<Int, String>()
        byName.forEach { (name, value) ->
            val field = runCatching { R.string::class.java.getField(name) }.getOrNull() ?: return@forEach
            idToText[field.getInt(null)] = value
        }
        return HelpTextProvider { id -> idToText[id] ?: "" }
    }

    private fun decode(raw: String): String = raw
        .replace("\\'", "'")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}
