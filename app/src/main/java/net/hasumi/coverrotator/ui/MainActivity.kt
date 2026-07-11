package net.hasumi.coverrotator.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.hasumi.coverrotator.di.Graph
import net.hasumi.coverrotator.model.RotationMode
import net.hasumi.coverrotator.ui.theme.CoverRotatorTheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_EXECUTE_ROTATION = "net.hasumi.coverrotator.EXECUTE_ROTATION"
        private const val REQUEST_CODE_SHIZUKU = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestShizukuPermissionIfNeeded()
        handleShortcutIntent(intent)

        setContent {
            CoverRotatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") {
                            MainScreen(
                                settingsRepository = Graph.settingsRepository,
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                settingsRepository = Graph.settingsRepository,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    /** 静的ショートカット(@xml/shortcuts)からの起動を処理する */
    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action != ACTION_EXECUTE_ROTATION) return
        val mode = when (intent.getStringExtra("command")) {
            "lock_90" -> RotationMode.LOCK_90
            "lock_270" -> RotationMode.LOCK_270
            "unlock" -> RotationMode.FREE
            else -> null
        } ?: return
        lifecycleScope.launch {
            Graph.rotationRepository.setRotationMode(mode)
        }
    }

    private fun requestShizukuPermissionIfNeeded() {
        try {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED &&
                !Shizuku.shouldShowRequestPermissionRationale()
            ) {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            }
        } catch (_: Throwable) {
            // Shizuku未インストールなどは無視(内蔵adbにフォールバック)
        }
    }
}
