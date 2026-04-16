package com.example.ussdagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.example.ussdagent.data.repo.AuthRepository
import com.example.ussdagent.data.repo.MonitoringRepository
import com.example.ussdagent.data.store.SecureStore
import com.example.ussdagent.ui.LoginScreen
import com.example.ussdagent.ui.SetupScreen
import com.example.ussdagent.ui.StatsScreen

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // If denied: app still works, but foreground service notification may be blocked/unreliable.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val store = SecureStore(applicationContext)
        val authRepo = AuthRepository(applicationContext, store)
        val monitoringRepo = MonitoringRepository(applicationContext)

        setContent {
            var loggedIn by remember { mutableStateOf(authRepo.isLoggedIn()) }
            var setupDone by remember { mutableStateOf(store.hasSetup()) }

            LaunchedEffect(loggedIn) {
                if (loggedIn && store.getRefreshToken() != null) {
                    authRepo.refreshSession()
                }
            }

            when {
                !loggedIn -> {
                    LoginScreen(
                        onLogin = { user, pass ->
                            val result = authRepo.login(user, pass)
                            val ok = result.isSuccess
                            if (ok) {
                                loggedIn = true
                                setupDone = store.hasSetup()
                            }
                            ok
                        }
                    )
                }

                !setupDone -> {
                    SetupScreen(
                        store = store,
                        onDone = { setupDone = true }
                    )
                }

                else -> {
                    StatsScreen(
                        repo = monitoringRepo,
                        onLogout = {
                            com.example.ussdagent.engine.EngineController.stop(applicationContext)
                            store.clearAuthSession()
                            loggedIn = false
                            setupDone = store.hasSetup()
                        },
                        appContext = applicationContext
                    )
                }
            }
        }
    }
}