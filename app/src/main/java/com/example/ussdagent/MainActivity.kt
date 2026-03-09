package com.example.ussdagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import com.example.ussdagent.data.repo.AuthRepository
import com.example.ussdagent.data.repo.MonitoringRepository
import com.example.ussdagent.data.store.SecureStore
import com.example.ussdagent.ui.LoginScreen
import com.example.ussdagent.ui.StatsScreen
import com.example.ussdagent.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // If denied: app still works, but foreground service notification may be blocked/unreliable.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Keep screen on while app is visible (helps during monitoring & ops)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ✅ Helps wake/show if screen was off (minSdk supports this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // Android 13+ requires runtime notification permission
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

            when {
                !loggedIn -> {
                    LoginScreen(
                        onLogin = { user, pass ->
                            val result = authRepo.login(user, pass)
                            val ok = result.isSuccess
                            if (ok) {
                                store.saveDeviceId("84fd0dd0-4583-4d60-a45e-7e4b962be2b9") // dev shortcut
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
                            store.clear()
                            loggedIn = false
                            setupDone = false
                        },
                        appContext = applicationContext
                    )
                }
            }
        }
    }
}