package uz.faceguard.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import uz.faceguard.app.feature.auth.CreatePinScreen
import uz.faceguard.app.feature.auth.LoginScreen
import uz.faceguard.app.feature.auth.RegisterScreen
import uz.faceguard.app.feature.auth.SplashScreen
import uz.faceguard.app.feature.auth.WelcomeScreen
import uz.faceguard.app.feature.child.ChildProfilesScreen
import uz.faceguard.app.feature.enrollment.FaceEnrollmentScreen
import uz.faceguard.app.feature.enrollment.SUBJECT_CHILD
import uz.faceguard.app.feature.enrollment.SUBJECT_PARENT
import uz.faceguard.app.feature.activity.ActivityLogScreen
import uz.faceguard.app.feature.help.HelpScreen
import uz.faceguard.app.feature.home.HomeScreen
import uz.faceguard.app.feature.privacy.PrivacyScreen
import uz.faceguard.app.feature.parent.ParentProfileScreen
import uz.faceguard.app.feature.protection.ProtectionDebugScreen
import uz.faceguard.app.feature.recognition.RecognitionDebugScreen
import uz.faceguard.app.feature.settings.SettingsScreen

object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val REGISTER = "register"
    const val LOGIN = "login"
    const val CREATE_PIN = "create_pin"
    const val HOME = "home"
    const val PARENT_PROFILE = "parent_profile"
    const val CHILD_PROFILES = "child_profiles"
    const val SETTINGS = "settings"
    const val RECOGNITION_DEBUG = "recognition_debug"
    const val PROTECTION_DEBUG = "protection_debug"
    const val PRIVACY = "privacy"
    const val HELP = "help"
    const val ACTIVITY_LOG = "activity_log"
    const val PARENT_FACE_ENROLLMENT = "parent_face_enrollment"
    const val CHILD_FACE_ENROLLMENT = "child_face_enrollment/{childId}"
    fun childFaceEnrollment(childId: Long) = "child_face_enrollment/$childId"
}

/**
 * Launch: Splash → Home when a persisted session exists, else → Welcome.
 * Logout (from Settings) clears session and returns to Welcome.
 */
@Composable
fun FaceGuardNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onReady = { hasSession ->
                    val target = if (hasSession) Routes.HOME else Routes.WELCOME
                    navController.navigate(target) { popUpTo(Routes.SPLASH) { inclusive = true } }
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
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenRecognition = { navController.navigate(Routes.RECOGNITION_DEBUG) },
                onOpenProtection = { navController.navigate(Routes.PROTECTION_DEBUG) },
                onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                onOpenHelp = { navController.navigate(Routes.HELP) },
                onOpenActivity = { navController.navigate(Routes.ACTIVITY_LOG) },
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
            )
        }
        composable(Routes.RECOGNITION_DEBUG) {
            RecognitionDebugScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PROTECTION_DEBUG) {
            ProtectionDebugScreen(onBack = { navController.popBackStack() })
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
            )
        }
    }
}
