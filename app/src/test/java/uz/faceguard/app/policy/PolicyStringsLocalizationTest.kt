package uz.faceguard.app.policy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group 4 localization guard: every new user-facing string must exist in the
 * default (Uzbek) resources and in the English/Russian mirrors. Reads the
 * resource files directly, so a missing translation fails the build fast.
 */
class PolicyStringsLocalizationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    private val requiredKeys = setOf(
        "child_policy_action",
        "child_policy_title",
        "child_policy_subtitle",
        "child_policy_choose_child",
        "child_policy_selected",
        "child_policy_apps_title",
        "child_policy_empty_apps",
        "child_policy_empty_apps_hint",
        "child_policy_no_children",
        "child_policy_no_children_hint",
        "child_policy_mode_allow",
        "child_policy_mode_limit",
        "child_policy_mode_block",
        "child_policy_state_explicit_allow",
        "child_policy_state_explicit_limit",
        "child_policy_state_explicit_block",
        "child_policy_state_default_allow",
        "child_policy_state_default_block",
        "child_policy_dialog_title",
        "child_policy_dialog_current",
        "child_policy_limit_label",
        "child_policy_minutes",
        "child_policy_limit_note",
        "child_policy_reset",
        "child_policy_open",
        "settings_load_error",
        // Group 5 activity log labels
        "activity_no_face",
        "activity_protection_released",
        // Group 6 foreground service notification
        "protection_service_channel_name",
        "protection_service_channel_description",
        "protection_service_notification_title",
        "protection_service_notification_text",
        // Group 7 accessibility service
        "accessibility_service_description",
        "protection_req_accessibility",
    )


    /** Phase 10 Parent Dashboard strings. */
    private val dashboardKeys = setOf(
        "dashboard_active_app",
        "dashboard_active_none",
        "dashboard_active_recovering",
        "dashboard_active_title",
        "dashboard_activity_empty",
        "dashboard_activity_open",
        "dashboard_activity_title",
        "dashboard_apps_count",
        "dashboard_apps_none_hint",
        "dashboard_apps_open",
        "dashboard_apps_title",
        "dashboard_capability_missing",
        "dashboard_capability_needed",
        "dashboard_capability_ok",
        "dashboard_capability_open",
        "dashboard_capability_ready",
        "dashboard_child_add",
        "dashboard_child_face_off",
        "dashboard_child_face_on",
        "dashboard_child_none",
        "dashboard_child_none_hint",
        "dashboard_child_selected",
        "dashboard_child_switch",
        "dashboard_child_title",
        "dashboard_error",
        "dashboard_error_hint",
        "dashboard_identity_child",
        "dashboard_identity_no_face",
        "dashboard_identity_none",
        "dashboard_identity_obstructed",
        "dashboard_identity_parent",
        "dashboard_identity_unknown",
        "dashboard_liveness_live",
        "dashboard_liveness_spoof",
        "dashboard_no_account",
        "dashboard_no_account_hint",
        "dashboard_policy_counts",
        "dashboard_policy_empty",
        "dashboard_policy_limit_line",
        "dashboard_policy_limit_line_unknown",
        "dashboard_policy_limits_title",
        "dashboard_policy_loading",
        "dashboard_policy_no_usage",
        "dashboard_policy_open",
        "dashboard_policy_title",
        "dashboard_profile_open",
        "dashboard_profile_title",
        "dashboard_protection_active",
        "dashboard_protection_inactive",
        "dashboard_protection_off",
        "dashboard_protection_on",
        "dashboard_protection_title",
        "dashboard_retry",
        "dashboard_subtitle",
        // Phase 4 Step 1B-8: the screen-time target card replaced the old
        // "dashboard_usage_*" placeholder, so those three keys are gone and these are the
        // card's keys instead.
        "screentime_target_title",
        "screentime_target_subtitle",
        "screentime_target_none_selected",
        "screentime_target_choose_hint",
        "screentime_target_active_hint",
        "screentime_target_no_children",
        "screentime_target_missing",
        "screentime_target_error_load",
        "screentime_target_error_save",
        "dashboard_value_unknown",
    )

    /** Phase 11 Notifications &amp; Requests strings. */
    private val phase11Keys = setOf(
        "dashboard_notifications_disabled",
        "dashboard_requests_none",
        "dashboard_requests_open",
        "dashboard_requests_pending",
        "notification_app_unknown",
        "notification_channel_protection_description",
        "notification_channel_protection_name",
        "notification_channel_requests_description",
        "notification_channel_requests_name",
        "notification_protection_blocked_body",
        "notification_protection_blocked_title",
        "notification_protection_released_body",
        "notification_protection_released_title",
        "notification_request_created_body",
        "notification_request_created_title",
        "request_approve",
        "request_approved_duration",
        "request_back",
        "request_child",
        "request_duration",
        "request_empty",
        "request_error",
        "request_extra_time",
        "request_extra_time_sent",
        "request_notifications_disabled",
        "request_reject",
        "request_resolved_note",
        "request_retry",
        "request_status",
        "request_status_approved",
        "request_status_cancelled",
        "request_status_expired",
        "request_status_pending",
        "request_status_rejected",
        "requests_subtitle",
        "requests_title",
    )

    /** Phase 12 security strings. */
    private val phase12Keys = setOf(
        "auth_locked_out",
        "security_recovery_required",
    )
    @Test
    fun everyLocaleDeclaresEveryGroup4Key() {
        val problems = mutableListOf<String>()
        locales.forEach { locale ->
            val names = stringNames(stringsFile(locale))
            val missing = (requiredKeys + dashboardKeys + phase11Keys + phase12Keys) - names
            if (missing.isNotEmpty()) problems += "$locale is missing $missing"
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test
    fun localesStayInSync() {
        val sizes = locales.map { stringNames(stringsFile(it)).size }
        assertEquals("values vs values-en", sizes[0], sizes[1])
        assertEquals("values vs values-ru", sizes[0], sizes[2])
    }

    private fun stringsFile(locale: String): File = File(repoRoot(), "app/src/main/res/$locale/strings.xml")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile ?: error("Repo root not found from ${System.getProperty("user.dir")}")
        }
    }

    private fun stringNames(file: File): Set<String> =
        Regex("""name="([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toSet()
}
