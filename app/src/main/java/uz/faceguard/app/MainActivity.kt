package uz.faceguard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import uz.faceguard.app.core.theme.FaceGuardTheme
import uz.faceguard.app.navigation.FaceGuardNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FaceGuardTheme {
                val navController = rememberNavController()
                FaceGuardNavHost(navController)
            }
        }
    }
}
