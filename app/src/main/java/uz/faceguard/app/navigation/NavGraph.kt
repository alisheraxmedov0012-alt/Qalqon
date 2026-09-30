package uz.faceguard.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import uz.faceguard.app.core.debug.DebugFlags
import uz.faceguard.app.core.i18n.StartupDestination
import uz.faceguard.app.core.notification.NotificationNavigation
import uz.faceguard.app.feature.auth.CreatePinScreen
import uz.faceguard.app.feature.auth.LoginScreen
import uz.faceguard.app.feature.auth.RegisterScreen
import uz.faceguard.app.feature.auth.SplashScreen
import uz.faceguard.app.feature.auth.WelcomeScreen
import uz.faceguard.app.feature.language.LanguageScreen
import uz.faceguard.app.feature.child.ChildProfilesScreen
import uz.faceguard.app.feature.enrollment.FaceEnrollmentScreen
import uz.faceguard.app.feature.enrollment.SUBJECT_CHILD
import uz.faceguard.app.feature.enrollment.SUBJECT_PARENT
import uz.faceguard.app.feature.activity.ActivityLogScreen
import uz.faceguard.app.feature.help.HelpScreen
import uz.faceguard.app.feature.home.HomeScreen
import uz.faceguard.app.feature.privacy.PrivacyScreen
import uz.faceguard.app.feature.parent.ParentProfileScreen
import uz.faceguard.app.feature.policy.ChildPolicyScreen
import uz.faceguard.app.feature.protection.ProtectionScreen
import uz.faceguard.app.feature.schedule.ScheduleArgs
import uz.faceguard.app.feature.schedule.ScheduleEditorScreen
import uz.faceguard.app.feature.schedule.ScheduleListScreen
import uz.faceguard.app.feature.eyesafety.EyeSafetyArgs
import uz.faceguard.app.feature.eyesafety.EyeSafetyScreen
import uz.faceguard.app.feature.requests.RequestsArgs
import uz.faceguard.app.feature.requests.RequestsScreen
import uz.faceguard.app.feature.recognition.RecognitionDebugScreen
import uz.faceguard.app.feature.settings.SettingsScreen

object Routes {
    const val SPLASH = "splash"
    const val LANGUAGE = "language"
    const val WELCOME = "welcome"
    const val REGISTER = "register"
    const val LOGIN = "login"
    const val CREATE_PIN = "create_pin"
    const val HOME = "home"
    const val PARENT_PROFILE = "parent_profile"
    const val CHILD_PROFILES = "child_profiles"
    const val SETTINGS = "settings"
    const val SETTINGS_APPS = "settings_apps"
    const val RECOGNITION_DEBUG = "recognition_debug"
    const val PROTECTION = "protection"
    const val PRIVACY = "privacy"
    const val HELP = "help"
    const val ACTIVITY_LOG = "activity_log"
    const val REQUESTS = "requests"
    const val PARENT_FACE_ENROLLMENT = "parent_face_enrollment"
    const val CHILD_FACE_ENROLLMENT = "child_face_enrollment/{childId}"
    const val CHILD_POLICY = "child_policy/{childId}"
    const val CHILD_SCHEDULES = "child_schedules/{childId}"
    const val CHILD_SCHEDULE_EDITOR = "child_schedule_editor/{childId}?scheduleId={scheduleId}"
    const val CHILD_EYE_SAFETY = "child_eye_safety/{childId}"
    fun childFaceEnrollment(childId: Long) = "child_face_enrollment/$childId"
    fun childPolicy(childId: Long) = "child_policy/$childId"
    fun childSchedules(childId: Long) = "child_schedules/$childId"
    fun childScheduleEditor(childId: Long, scheduleId: Long = -1L) =
        "child_schedule_editor/$childId?scheduleId=$scheduleId"
    fun childEyeSafety(childId: Long) = "child_eye_safety/$childId"
    fun requests(requestId: Long = -1L) =
        if (requestId <= 0L) REQUESTS else "$REQUESTS?${RequestsArgs.REQUEST_ID}=$requestId"
}

/**
 * Phase 12: maps an *untrusted* notification destination extra to a validated
 * internal route, or `null` when it is not one of the known notification
 * destinations.
 *
 * `MainActivity` is exported (it is the launcher), so this value can originate
 * from any other app on the device. It was previously navigated verbatim, which
 * let an external caller drive the app into an arbitrary internal route and skip
 * the startup/auth gate. Only the single destination that genuinely needs an
 * explicit route is honoured here; the "dashboard" destination is already
 * satisfied by the normal start flow (Splash -> Home when signed in, Welcome when
 * signed out), so it maps to `null` and can never force a signed-out caller past
 * the auth gate.
 *
 * Pure (no Android/NavController), so the validation is unit-testable.
 */
fun notificationRouteFor(rawDestination: String?): String? = when (rawDestination) {
    NotificationNavigation.DESTINATION_REQUESTS -> Routes.REQUESTS
    else -> null
}

/**
 * Launch: Splash → Home when a persisted session exists, else → Welcome.
 * Logout (from Settings) clears session and returns to Welcome.
 */
@Composable
fun FaceGuardNavHost(
    navController: NavHostController,
    /**
     * Phase 11: destination requested by a notification click (see
     * `NotificationNavigation`). Routed through the existing graph — no duplicate
     * navigation architecture, and the destination validates ownership itself.
     */
    requestedDestination: String? = null,
) {
    // A notification click opens the app and asks for one destination. The extra is
    // untrusted (MainActivity is exported) so it is validated against the known
    // notification destinations before use; anything else — including a crafted
    // internal route — is ignored. Ownership is still validated by the
    // destination's own account-scoped queries.
    LaunchedEffect(requestedDestination) {
        val route = notificationRouteFor(requestedDestination) ?: return@LaunchedEffect
        runCatching { navController.navigate(route) }
    }

    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onReady = { destination ->
                    val target = when (destination) {
                        StartupDestination.LANGUAGE_SELECTION -> Routes.LANGUAGE
                        StartupDestination.HOME -> Routes.HOME
                        StartupDestination.WELCOME -> Routes.WELCOME
                    }
                    navController.navigate(target) { popUpTo(Routes.SPLASH) { inclusive = true } }
                },
            )
        }
        composable(Routes.LANGUAGE) {
            LanguageScreen(
                onDone = {
                    // Resume the normal onboarding flow. Registration/login is the
                    // correct entry point here: the picker only appears before a
                    // language exists, which is always before an account does.
                    navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onRegister = { navController.navigate(Routes.REGISTER) },
                onLogin = { navController.navigate(Routes.LOGIN) },
            )
        }
        composable(Routes.REGISTER) {
            RegisterScreen(
                onBack = { navController.popBackStack() },
                onNext = { navController.navigate(Routes.CREATE_PIN) },
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                onLogin = {
                    navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.CREATE_PIN) {
            CreatePinScreen(
                onCreated = {
                    navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenParent = { navController.navigate(Routes.PARENT_PROFILE) },
                onOpenChildren = { navController.navigate(Routes.CHILD_PROFILES) },
                onOpenProtectedApps = { navController.navigate(Routes.SETTINGS_APPS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenRecognition = { navController.navigate(Routes.RECOGNITION_DEBUG) },
                onOpenProtection = { navController.navigate(Routes.PROTECTION) },
                onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                onOpenHelp = { navController.navigate(Routes.HELP) },
                onOpenActivity = { navController.navigate(Routes.ACTIVITY_LOG) },
                onOpenChildPolicy = { childId -> navController.navigate(Routes.childPolicy(childId)) },
                onOpenRequests = { navController.navigate(Routes.requests()) },
                onOpenChildSchedules = { childId -> navController.navigate(Routes.childSchedules(childId)) },
                onOpenChildEyeSafety = { childId -> navController.navigate(Routes.childEyeSafety(childId)) },
            )
        }
        composable(Routes.PARENT_PROFILE) {
            ParentProfileScreen(
                onBack = { navController.popBackStack() },
                onEnroll = { navController.navigate(Routes.PARENT_FACE_ENROLLMENT) },
            )
        }
        composable(
            route = Routes.CHILD_FACE_ENROLLMENT,
            arguments = listOf(navArgument("childId") { type = NavType.LongType }),
        ) {
            entry ->
            FaceEnrollmentScreen(
                onBack = { navController.popBackStack() },
                subject = SUBJECT_CHILD,
                childId = entry.arguments?.getLong("childId") ?: -1L,
            )
        }
        composable(Routes.PARENT_FACE_ENROLLMENT) {
            FaceEnrollmentScreen(
                onBack = { navController.popBackStack() },
                subject = SUBJECT_PARENT,
                childId = -1L,
            )
        }
        composable(Routes.CHILD_PROFILES) {
            ChildProfilesScreen(
                onBack = { navController.popBackStack() },
                onEnrollChild = { childId ->
                    navController.navigate(Routes.childFaceEnrollment(childId))
                },
                onOpenPolicy = { childId ->
                    navController.navigate(Routes.childPolicy(childId))
                },
            )
        }
        composable(
            route = Routes.CHILD_POLICY,
            arguments = listOf(navArgument("childId") { type = NavType.LongType }),
        ) {
            ChildPolicyScreen(
                onBack = { navController.popBackStack() },
                onOpenChildren = { navController.navigate(Routes.CHILD_PROFILES) },
                onOpenSchedules = { childId -> navController.navigate(Routes.childSchedules(childId)) },
                onOpenEyeSafety = { childId -> navController.navigate(Routes.childEyeSafety(childId)) },
            )
        }
        composable(
            route = Routes.CHILD_EYE_SAFETY,
            arguments = listOf(navArgument(EyeSafetyArgs.CHILD_ID) { type = NavType.LongType }),
        ) {
            EyeSafetyScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.CHILD_SCHEDULES,
            arguments = listOf(navArgument(ScheduleArgs.CHILD_ID) { type = NavType.LongType }),
        ) { entry ->
            val childId = entry.arguments?.getLong(ScheduleArgs.CHILD_ID) ?: -1L
            ScheduleListScreen(
                onBack = { navController.popBackStack() },
                onAddSchedule = { navController.navigate(Routes.childScheduleEditor(childId)) },
                onEditSchedule = { scheduleId ->
                    navController.navigate(Routes.childScheduleEditor(childId, scheduleId))
                },
            )
        }
        composable(
            route = Routes.CHILD_SCHEDULE_EDITOR,
            arguments = listOf(
                navArgument(ScheduleArgs.CHILD_ID) { type = NavType.LongType },
                navArgument(ScheduleArgs.SCHEDULE_ID) {
                    type = NavType.LongType
                    defaultValue = ScheduleArgs.NEW_SCHEDULE_ID
                },
            ),
        ) {
            ScheduleEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        composable(
            route = "${Routes.REQUESTS}?${RequestsArgs.REQUEST_ID}={${RequestsArgs.REQUEST_ID}}",
            arguments = listOf(
                navArgument(RequestsArgs.REQUEST_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) {
            RequestsScreen(onBack = { navController.popBackStack() })
        }
        // Phase 12: developer-only. Gated by BuildConfig.DEBUG (via DebugFlags) so the
        // route does not exist in a release build at all — previously it was always
        // registered, so an internal route (reachable through the exported activity)
        // could surface QALQON's live recognition diagnostics in production. The
        // debug build keeps the screen unchanged.
        if (DebugFlags.DEBUG_SCREENS_ENABLED) {
            composable(Routes.RECOGNITION_DEBUG) {
                RecognitionDebugScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(Routes.PROTECTION) {
            ProtectionScreen(
                onBack = { navController.popBackStack() },
                onOpenParentProfile = { navController.navigate(Routes.PARENT_PROFILE) },
                onOpenProtectedApps = { navController.navigate(Routes.SETTINGS_APPS) },
            )
        }
        composable(Routes.PRIVACY) {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.HELP) {
            HelpScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ACTIVITY_LOG) {
            ActivityLogScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
                initialTab = 0,
            )
        }
        composable(Routes.SETTINGS_APPS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
                initialTab = 1,
            )
        }
    }
}
