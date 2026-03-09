package com.example.ussdagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    onLogin: suspend (String, String) -> Boolean
) {
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("USSD Agent Login", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        if (errorMsg != null) {
            Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(10.dp))
        }

        Button(
            onClick = {
                errorMsg = null
                loading = true
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "Logging in..." else "Login")
        }

        // Run login when loading switches to true
        if (loading) {
            LaunchedEffect(Unit) {
                val ok = onLogin(username.trim(), password)
                loading = false
                if (!ok) errorMsg = "Login failed. Check username/password and server connection."
            }
        }
    }
}