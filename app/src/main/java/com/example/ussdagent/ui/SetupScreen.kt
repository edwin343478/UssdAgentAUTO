package com.example.ussdagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ussdagent.data.store.SecureStore
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    store: SecureStore,
    onDone: () -> Unit
) {
    var yasPin by remember { mutableStateOf("") }
    var vodacomPin by remember { mutableStateOf("") }
    var assistantId by remember { mutableStateOf("") }

    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Setup (Required)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Enter credentials used on the phone. These are stored encrypted on this device.")

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = yasPin,
            onValueChange = { yasPin = it.filter { ch -> ch.isDigit() }.take(4) },
            label = { Text("Yas/Tigo PIN (4 digits)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = vodacomPin,
            onValueChange = { vodacomPin = it.filter { ch -> ch.isDigit() }.take(4) },
            label = { Text("Vodacom (M-PESA) PIN (4 digits)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = assistantId,
            onValueChange = { assistantId = it.uppercase().filter { ch -> ch.isLetter() }.take(3) },
            label = { Text("M-PESA Assistant ID (3 letters)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                if (saving) return@Button
                error = null

                // Validation
                if (yasPin.length != 4) { error = "Yas PIN must be 4 digits."; return@Button }
                if (vodacomPin.length != 4) { error = "Vodacom PIN must be 4 digits."; return@Button }
                if (assistantId.length != 3) { error = "Assistant ID must be 3 letters."; return@Button }

                saving = true
                scope.launch {
                    store.saveYasPin(yasPin)
                    store.saveVodacomPin(vodacomPin)
                    store.saveMpesaAssistantId(assistantId)
                    saving = false
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving
        ) {
            Text(if (saving) "Saving..." else "Save Setup")
        }
    }
}