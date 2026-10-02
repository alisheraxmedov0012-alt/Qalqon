package uz.faceguard.app.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import uz.faceguard.app.core.debug.DebugFlags
import uz.faceguard.app.core.i18n.StartupDestination
import uz.faceguard.app.core.notification.NotificationNavigation
import uz.faceguard.app.feature.auth.CreatePinScreen
import uz.faceguard.app.feature.auth.LoginScreen
import uz.faceguard.app.feature.auth.PinUnlockScreen
import uz.faceguard.app.feature.auth.RegisterScreen
import uz.faceguard.app.feature.auth.SplashScreen
import uz.faceguard.app.feature.auth.WelcomeScreen
import uz.faceguard.app.feature.language.LanguageScreen
import uz.faceguard.app.feature.child.ChildDetailScreen
import uz.faceguard.app.feature.child.ChildProfilesScreen
import uz.faceguard.app.feature.enrollment.FaceEnrollmentScreen
import uz.faceguard.app.feature.enrollment.SUBJECT_CHILD
import uz.faceguard.app.feature.enrollment.SUBJECT_PARENT
import uz.faceguard.app.feature.activity.ActivityLogScreen
import uz.faceguard.app.feature.help.HelpAssistantScreen
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
import uz.faceguard.app.feature.settings.AppearanceSettingsScreen
import uz.faceguard.app.feature.settings.DeveloperSettingsScreen
import uz.faceguard.app.feature.settings.FamilySettingsScreen
import uz.faceguard.app.feature.settings.NotificationsSettingsScreen
import uz.faceguard.app.feature.settings.PrivacySettingsScreen
import uz.faceguard.app.feature.settings.ProtectedAppsScreen
import uz.faceguard.app.feature.settings.ProtectionSettingsScreen
import uz.faceguard.app.feature.settings.SecuritySettingsScreen
import uz.faceguard.app.feature.settings.SettingsScreen
import uz.faceguard.app.feature.settings.SupportSettingsScreen

object Routes {
    const val SPLASH = "splash"
    const val LANGUAGE = "language"

    /** Startup PIN gate: shown whenever a registered account exists but the UI is locked. */
    const val PIN_UNLOCK = "pin_unlock"
    const val WELCOME = "welcome"
    const val REGISTER = "register"
    const val LOGIN = "login"
    const val CREATE_PIN = "create_pin"
    const val HOME = "home"
    const val PARENT_PROFILE = "parent_profile"
    const val CHILD_PROFILES = "child_profiles"
    const val SETTINGS = "settings"
    const val SETTINGS_APPS = "settings_apps"

    /**
     * UI/UX redesign, Phase 6: one route per Settings category. They are siblings of
     * `settings_apps` (reusing the existing protected-apps destination) rather than new
     * bottom-navigation items, and they group the existing settings by function.
     */
    const val SETTINGS_PROTECTION = "settings_protection"
    const val SETTINGS_FAMILY = "settings_family"
    const val SETTINGS_SECURITY = "settings_security"
    const val SETTINGS_APPEARANCE = "settings_appearance"
    const val SETTINGS_NOTIFICATIONS = "settings_notifications"
    const val SETTINGS_PRIVACY = "settings_privacy"
    const val SETTINGS_SUPPORT = "settings_support"
    const val SETTINGS_DEVELOPER = "settings_developer"
    const val RECOGNITION_DEBUG = "recognition_debug"
    const val PROTECTION = "protection"
    const val PRIVACY = "privacy"
    const val HELP = "help"

    /** Settings → Yordam → the Qalqon Assistant (local, offline). */
    const val HELP_ASSISTANT = "help_assistant"
    const val ACTIVITY_LOG = "activity_log"
    const val REQUESTS = "requests"
    const val PARENT_FACE_ENROLLMENT = "parent_face_enrollment"
    const val CHILD_DETAIL = "child_detail/{childId}"
    const val CHILD_FACE_ENROLLMENT = "child_face_enrollment/{childId}"
    const val CHILD_POLICY = "child_policy/{childId}"
    const val CHILD_SCHEDULES = "child_schedules/{childId}"
    const val CHILD_SCHEDULE_EDITOR = "child_schedule_editor/{childId}?scheduleId={scheduleId}"
    const val CHILD_EYE_SAFETY = "child_eye_safety/{childId}"

    /**
     * UI/UX redesign, Phase 4: optional section selector on the child policy screen,
     * so the Child Detail hub can open App rules or Screen time directly on the same
     * screen (and the same ViewModel) instead of duplicating either destination.
     */
    const val CHILD_POLICY_SECTION = "section"
    const val CHILD_POLICY_SECTION_SCREEN_TIME = "screen_time"

    fun childDetail(childId: Long) = "child_detail/$childId"
    fun childFaceEnrollment(childId: Long) = "child_face_enrollment/$childId"
    fun childPolicy(childId: Long) = "child_policy/$childId"
    fun childScreenTime(childId: Long) =
        "child_policy/$childId?$CHILD_POLICY_SECTION=$CHILD_POLICY_SECTION_SCREEN_TIME"
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
 * A resolved destination is additionally only navigated once the parent UI is
 * unlocked, so a notification click cannot skip the startup PIN gate either.
 *
 * Pure (no Android/NavController), so the validation is unit-testable.
 */
fun notificationRouteFor(rawDestination: String?): String? = when (rawDestination) {
    NotificationNavigation.DESTINATION_REQUESTS -> Routes.REQUESTS
    else -> null
}

/**
 * Routes that expose parental controls and therefore require an unlocked UI.
 *
 * Everything a child must not reach without the parent PIN is listed here —
 * including the Settings screen, which is where the app language is changed.
 *
 * Matching is segment-aware (`route` or `route/...`), so a future route that merely
 * *starts with* a protected name (e.g. "homework") is not caught by accident.
 * `settings_apps` is therefore listed explicitly rather than relying on the
 * `settings` prefix.
 */
private val PROTECTED_ROUTE_PREFIXES = listOf(
    Routes.HOME,
    Routes.PARENT_PROFILE,
    Routes.CHILD_PROFILES,
    Routes.SETTINGS,
    Routes.SETTINGS_APPS,
    // Phase 6: every Settings category page is parental control and must stay behind
    // the PIN gate alongside the Settings hub itself.
    Routes.SETTINGS_PROTECTION,
    Routes.SETTINGS_FAMILY,
    Routes.SETTINGS_SECURITY,
    Routes.SETTINGS_APPEARANCE,
    Routes.SETTINGS_NOTIFICATIONS,
    Routes.SETTINGS_PRIVACY,
    Routes.SETTINGS_SUPPORT,
    Routes.SETTINGS_DEVELOPER,
    Routes.PROTECTION,
    Routes.PRIVACY,
    Routes.HELP,
    // The Qalqon Assistant is part of the parent Help area and stays behind the PIN gate.
    Routes.HELP_ASSISTANT,
    Routes.ACTIVITY_LOG,
    Routes.REQUESTS,
    Routes.RECOGNITION_DEBUG,
    Routes.PARENT_FACE_ENROLLMENT,
    // Phase 4: the new Child Detail hub is a child-management route and must stay
    // behind the PIN gate like every other child-scoped destination.
    "child_detail",
    "child_face_enrollment",
    "child_policy",
    "child_schedules",
    "child_schedule_editor",
    "child_eye_safety",
)

/**
 * True when [route] is part of the parent control UI and must not be reachable
 * while the app is locked. Query suffixes are ignored so `requests?requestId=5`
 * is classified by its path.
 */
fun isProtectedRoute(route: String?): Boolean {
    val path = route?.substringBefore('?')?.takeIf { it.isNotBlank() } ?: return false
    return PROTECTED_ROUTE_PREFIXES.any { path == it || path.startsWith("$it/") }
}

/**
 * The startup lock gate, as a pure rule.
 *
 * Returns the route the user must be sent to instead of [route] when the UI is
 * locked, or `null` when [route] may be shown. Because this is applied to whatever
 * destination the graph actually reaches, it also covers the routes a locked user
 * could otherwise slip into — a direct navigation call, a restored navigation
 * stack after process death, a notification deep link or a back-stack restore —
 * without any per-screen checks and without touching the notification whitelist.
 */
fun lockRedirectFor(route: String?, isUnlocked: Boolean): String? =
    if (!isUnlocked && isProtectedRoute(route)) Routes.PIN_UNLOCK else null

/**
 * Switches to a primary top-level destination (UI/UX redesign, Phase 2).
 *
 * Implements the standard Material bottom-navigation behaviour on the one existing
 * controller: switching tabs never grows the back stack (so
 * `Home → Children → Activity → Settings → Activity` cannot happen) and each tab's
 * saved state is restored when it is re-selected. Home is the stable root of the
 * authenticated UI (every successful auth path lands there with `popUpTo(0)`), so
 * popping up to it keeps the back behaviour intuitive without a second start
 * destination.
 */
private fun NavHostController.navigateToTopLevel(destination: QalqonTopLevelDestination) {
    // Post-UI correction: decide explicitly instead of relying on launchSingleTop, which
    // collapsed a re-tap of the active tab (or a restored tab that resolved to the current
    // destination) to a silent no-op and made the screen appear not to switch.
    when (val decision = BottomNavigationPolicy.resolve(currentDestination?.route, destination)) {
        is BottomNavigationPolicy.Decision.ResetToRoot ->
            popBackStack(decision.route, inclusive = false)

        is BottomNavigationPolicy.Decision.Switch ->
            navigate(decision.route) {
                popUpTo(Routes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
    }
}

/**
 * Launch: Splash resolves the language, the registered account and the process
 * lock into one destination. A registered account on a later launch is **not**
 * unlocked, so it lands on the PIN screen; Home is only reachable once this
 * process has passed the PIN check.
 *
 * The lock gate below is applied to every destination the graph reaches, so a
 * restored navigation stack, a deep link or a notification click cannot expose the
 * parental controls while locked. Logout (from Settings) clears the session and
 * returns to Welcome, exactly as before.
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
    /** Whether the parent UI is unlocked for this process (see `AppLockState`). */
    isUnlocked: Boolean = false,
    /**
     * The authoritative lock value read *at decision time*.
     *
     * [isUnlocked] is what recomposes (and restarts the effects); this provider is
     * what the gate actually consults, so a navigation that happens in the same
     * frame as a successful unlock (login/create-PIN mark the UI unlocked just
     * before signalling success) can never be bounced back to the PIN screen by a
     * stale captured value.
     */
    isUnlockedNow: () -> Boolean = { isUnlocked },
) {
    // A notification click opens the app and asks for one destination. The extra is
    // untrusted (MainActivity is exported) so it is validated against the known
    // notification destinations before use; anything else — including a crafted
    // internal route — is ignored. Ownership is still validated by the
    // destination's own account-scoped queries. A locked UI is never driven
    // anywhere: the startup PIN gate has to be cleared first.
    LaunchedEffect(requestedDestination, isUnlocked) {
        val route = notificationRouteFor(requestedDestination) ?: return@LaunchedEffect
        if (!isUnlockedNow()) return@LaunchedEffect
        runCatching { navController.navigate(route) }
    }

    // The startup lock gate. Observing the back stack (rather than checking each
    // screen) is what makes it unbypassable: whatever destination is reached —
    // including a restored stack after process death or a saved navigation state —
    // a protected route while locked is replaced by the PIN screen.
    LaunchedEffect(navController, isUnlocked) {
        if (isUnlocked) return@LaunchedEffect
        navController.currentBackStackEntryFlow.collect { entry ->
            val redirect = lockRedirectFor(entry.destination.route, isUnlockedNow())
                ?: return@collect
            navController.navigate(redirect) { popUpTo(0) { inclusive = true } }
        }
    }

    // Phase 2: the currently selected primary destination, derived from the single
    // existing back stack. Null on every non-tab route, which is what keeps the
    // bottom bar off the onboarding, PIN-gate and child-detail screens.
    val selectedDestination = QalqonTopLevelDestination.forRoute(
        navController.currentBackStackEntryAsState().value?.destination?.route,
    )

    QalqonAppShell(
        selected = selectedDestination,
        onSelect = { destination -> navController.navigateToTopLevel(destination) },
    ) { innerPadding ->
        QalqonNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // The shell already reserves the bar (and the system bars) area;
                // consuming those insets stops each screen's own Scaffold from
                // applying them a second time.
                .consumeWindowInsets(innerPadding),
        )
    }
}

/**
 * The existing QALQON route graph, hosted inside the shell.
 *
 * Extracted verbatim from [FaceGuardNavHost] so the graph is not duplicated: the
 * shell adds the bottom navigation *around* the routes, the routes themselves —
 * every existing destination, argument and debug gate — are unchanged.
 */
@Composable
private fun QalqonNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        modifier = modifier,
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onReady = { destination ->
                    val target = when (destination) {
                        StartupDestination.LANGUAGE_SELECTION -> Routes.LANGUAGE
                        StartupDestination.PIN_UNLOCK -> Routes.PIN_UNLOCK
                        StartupDestination.HOME -> Routes.HOME
                        StartupDestination.WELCOME -> Routes.WELCOME
                    }
                    navController.navigate(target) { popUpTo(Routes.SPLASH) { inclusive = true } }
                },
            )
        }
        composable(Routes.PIN_UNLOCK) {
            PinUnlockScreen(
                onUnlocked = {
                    navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
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
            // Phase 3: the redesigned dashboard. Every callback maps to an existing
            // route; Parent profile / schedules / eye safety remain reachable through
            // the Protection and child-policy screens, and Activity is a bottom tab.
            HomeScreen(
                onOpenChildren = { navController.navigate(Routes.CHILD_PROFILES) },
                onOpenProtectedApps = { navController.navigate(Routes.SETTINGS_APPS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenProtection = { navController.navigate(Routes.PROTECTION) },
                onOpenRequests = { navController.navigate(Routes.requests()) },
                // Phase 4: Home's child card now opens the Child Detail hub, which is
                // the single entry point for every child-scoped control.
                onOpenChildPolicy = { childId -> navController.navigate(Routes.childDetail(childId)) },
                onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                onOpenHelp = { navController.navigate(Routes.HELP) },
                onOpenRecognition = { navController.navigate(Routes.RECOGNITION_DEBUG) },
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
                onOpenChild = { childId -> navController.navigate(Routes.childDetail(childId)) },
                onEnrollChild = { childId ->
                    navController.navigate(Routes.childFaceEnrollment(childId))
                },
            )
        }
        // Phase 4: the Child Detail hub. Every control keeps the child context by
        // navigating to the existing child-scoped route with the same id.
        composable(
            route = Routes.CHILD_DETAIL,
            arguments = listOf(navArgument("childId") { type = NavType.LongType }),
        ) {
            entry ->
            val childId = entry.arguments?.getLong("childId") ?: -1L
            ChildDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenApps = { id -> navController.navigate(Routes.childPolicy(id)) },
                onOpenScreenTime = { id -> navController.navigate(Routes.childScreenTime(id)) },
                onOpenSchedule = { id -> navController.navigate(Routes.childSchedules(id)) },
                onOpenEyeSafety = { id -> navController.navigate(Routes.childEyeSafety(id)) },
                onOpenFace = { id -> navController.navigate(Routes.childFaceEnrollment(id)) },
                onOpenRequests = { navController.navigate(Routes.requests()) },
            )
        }
        composable(
            route = "${Routes.CHILD_POLICY}?${Routes.CHILD_POLICY_SECTION}={${Routes.CHILD_POLICY_SECTION}}",
            arguments = listOf(
                navArgument("childId") { type = NavType.LongType },
                navArgument(Routes.CHILD_POLICY_SECTION) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            ChildPolicyScreen(
                onBack = { navController.popBackStack() },
                onOpenChildren = { navController.navigate(Routes.CHILD_PROFILES) },
                onOpenSchedules = { childId -> navController.navigate(Routes.childSchedules(childId)) },
                onOpenEyeSafety = { childId -> navController.navigate(Routes.childEyeSafety(childId)) },
                focusScreenTime = entry.arguments?.getString(Routes.CHILD_POLICY_SECTION) ==
                    Routes.CHILD_POLICY_SECTION_SCREEN_TIME,
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
        // Help is a first-class bottom-navigation destination (see
        // QalqonTopLevelDestination.HELP), so it reuses this existing route and renders
        // without a back arrow, exactly like the other tabs. The Qalqon Assistant stays
        // its own pushed route behind the same PIN gate.
        composable(Routes.HELP) {
            HelpScreen(
                onOpenAssistant = { navController.navigate(Routes.HELP_ASSISTANT) },
            )
        }
        composable(Routes.HELP_ASSISTANT) {
            HelpAssistantScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ACTIVITY_LOG) {
            // Phase 5: Activity is a top-level bottom-navigation destination, so it has
            // no back arrow; its pending requests open the existing requests route.
            ActivityLogScreen(
                onOpenRequests = { navController.navigate(Routes.requests()) },
            )
        }
        // Phase 6: Settings is a top-level tab whose hub links to one page per
        // category. Every category route is a sibling of `settings_apps` and stays
        // behind the same PIN gate; the hub itself has no back arrow.
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenCategory = { category -> navController.navigate(category.route) },
            )
        }
        composable(Routes.SETTINGS_APPS) {
            ProtectedAppsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_PROTECTION) {
            ProtectionSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenProtectedApps = { navController.navigate(Routes.SETTINGS_APPS) },
            )
        }
        composable(Routes.SETTINGS_FAMILY) {
            FamilySettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenParentProfile = { navController.navigate(Routes.PARENT_PROFILE) },
                onOpenChildren = { navController.navigate(Routes.CHILD_PROFILES) },
            )
        }
        composable(Routes.SETTINGS_SECURITY) {
            SecuritySettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.SETTINGS_APPEARANCE) {
            AppearanceSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_NOTIFICATIONS) {
            NotificationsSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_PRIVACY) {
            PrivacySettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.SETTINGS_SUPPORT) {
            SupportSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenHelp = { navController.navigate(Routes.HELP) },
            )
        }
        if (DebugFlags.DEBUG_SCREENS_ENABLED) {
            composable(Routes.SETTINGS_DEVELOPER) {
                DeveloperSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRecognition = { navController.navigate(Routes.RECOGNITION_DEBUG) },
                )
            }
        }
    }
}
