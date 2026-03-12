package com.example.ussdagent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ussdagent.data.api.MonitoringSummary
import com.example.ussdagent.data.repo.MonitoringRepository
import com.example.ussdagent.data.repo.UnauthorizedException
import com.example.ussdagent.data.store.SecureStore
import com.example.ussdagent.engine.CurrentJobState
import com.example.ussdagent.engine.EngineController
import com.example.ussdagent.engine.EngineState
import com.example.ussdagent.engine.ws.EngineWsManager
import com.example.ussdagent.telephony.SimManager
import com.example.ussdagent.telephony.SimSlotInfo
import com.example.ussdagent.ussd.UssdAutomationBus
import com.example.ussdagent.ussd.UssdDialBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val JOB_TIMEOUT_SECONDS = 1 * 60 // 8 minutes

@Composable
fun StatsScreen(
    repo: MonitoringRepository,
    onLogout: () -> Unit,
    appContext: Context
) {
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var summaryData by remember { mutableStateOf<MonitoringSummary?>(null) }

    val scope = rememberCoroutineScope()

    val engineMsg by EngineState.lastMessage.collectAsState()
    val currentJob by CurrentJobState.job.collectAsState()

    val ussdSnap by UssdAutomationBus.snapshot.collectAsState()

    // Auto Dial toggle + status message
    var autoDialEnabled by remember { mutableStateOf(true) }
    var lastAutoDialDispatchKey by remember { mutableStateOf<String?>(null) }
    var autoDialStatusMsg by remember { mutableStateOf<String?>(null) }

    // SecureStore setup status
    val store = remember { SecureStore(appContext) }
    val yasSaved = !store.getYasPin().isNullOrBlank()
    val vodacomSaved = !store.getVodacomPin().isNullOrBlank()
    val assistantId = store.getMpesaAssistantId()
    val assistantSaved = !assistantId.isNullOrBlank()
    var revealAssistantId by remember { mutableStateOf(false) }

    // SIM permission + status
    var hasPhoneStatePerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val phoneStatePermLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPhoneStatePerm = granted
        }

    val simManager = remember { SimManager(appContext) }
    val sims: List<SimSlotInfo> = remember(hasPhoneStatePerm) {
        if (hasPhoneStatePerm) simManager.getActiveSims() else emptyList()
    }

    fun simLooksCorrect(slot: Int, carrier: String): Boolean {
        val c = carrier.lowercase()
        return when (slot) {
            1 -> c.contains("yas")
            2 -> c.contains("vodacom")
            else -> true
        }
    }

    // Confirm SIM dialog state
    var showConfirmDial by remember { mutableStateOf(false) }
    var dialUssd by remember { mutableStateOf<String?>(null) }
    var dialSimSlot by remember { mutableStateOf<Int?>(null) }
    var dialSimCarrier by remember { mutableStateOf<String?>(null) }
    var dialError by remember { mutableStateOf<String?>(null) }
    var dialJobId by remember { mutableStateOf<String?>(null) }
    var dialNetwork by remember { mutableStateOf<String?>(null) }

    // Job session checklist state (resets per jobId)
    val jobId = currentJob?.jobId

    var simConfirmed by remember(jobId) { mutableStateOf(false) }
    var enteredCustomer by remember(jobId) { mutableStateOf(false) }
    var enteredAmount by remember(jobId) { mutableStateOf(false) }
    var enteredAssistant by remember(jobId) { mutableStateOf(false) }
    var enteredPin by remember(jobId) { mutableStateOf(false) }

    // Failure presets
    val failureReasons = listOf(
        "Network busy / USSD failed",
        "Insufficient balance",
        "Wrong PIN",
        "Customer number invalid",
        "Agent limit reached",
        "Operator canceled",
        "Operator timeout"
    )
    var selectedFailReason by remember(jobId) { mutableStateOf(failureReasons.first()) }
    var failMenuExpanded by remember { mutableStateOf(false) }

    // Aggressive timeout state (counts down, auto-fails once)
    var remainingSeconds by remember(jobId) { mutableIntStateOf(JOB_TIMEOUT_SECONDS) }
    var timeoutTriggered by remember(jobId) { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            errorMsg = null

            val result = repo.fetchSummary()

            loading = false
            if (result.isSuccess) {
                summaryData = result.getOrNull()
            } else {
                val ex = result.exceptionOrNull()
                if (ex is UnauthorizedException) {
                    errorMsg = ex.message ?: "Session expired. Please login again."
                    onLogout()
                } else {
                    errorMsg = ex?.message ?: "Failed to load stats"
                }
            }
        }
    }

    fun ussdCodeForNetwork(network: String?): String {
        return when (network?.trim()?.uppercase()) {
            "TIGO" -> "*150*01#"
            "VODACOM", "MPESA" -> "*150*00#"
            else -> "*150#"
        }
    }

    fun openDialerWithUssd(ussd: String) {
        val tel = "tel:" + ussd.replace("#", "%23")
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse(tel)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun beginSafeDialForJob() {
        dialError = null
        val job = currentJob ?: run {
            dialError = "No current job."
            return
        }

        val slot = job.simSlot
        if (slot == null || (slot != 1 && slot != 2)) {
            dialError = "Job has invalid sim_slot."
            return
        }

        if (!hasPhoneStatePerm) {
            dialError = "Grant Phone permission to validate SIM slots."
            return
        }

        val sim = simManager.getSimForSlot(slot)
        if (sim == null) {
            dialError = "SIM Slot $slot is not active. Insert SIM or enable it."
            return
        }

        if (!simLooksCorrect(slot, sim.carrierName)) {
            dialError = "SIM Slot $slot looks wrong: '${sim.carrierName}'. Expected: " +
                    (if (slot == 1) "Yas" else "Vodacom Tanzania") +
                    ". Fix SIM positions before dialing."
            return
        }

        val net = job.network?.uppercase()
        if (net == "TIGO" && !yasSaved) {
            dialError = "Setup missing: Yas PIN not saved."
            return
        }
        if ((net == "VODACOM" || net == "MPESA") && (!vodacomSaved || !assistantSaved)) {
            dialError = "Setup missing: Vodacom PIN or Assistant ID not saved."
            return
        }

        dialUssd = ussdCodeForNetwork(job.network)
        dialSimSlot = slot
        dialSimCarrier = sim.carrierName
        dialJobId = job.jobId
        dialNetwork = job.network
        showConfirmDial = true

        EngineWsManager.client?.sendEvent(
            jobId = job.jobId,
            eventType = "SAFE_DIAL_REQUESTED",
            payload = mapOf("sim_slot" to slot, "carrier" to sim.carrierName, "network" to job.network)
        )
    }

    // Auto-dial Step 1 when a new job arrives (with skip reason)
    LaunchedEffect(jobId, autoDialEnabled, hasPhoneStatePerm) {
        autoDialStatusMsg = null

        val job = currentJob ?: run {
            autoDialStatusMsg = if (autoDialEnabled) "Auto dial skipped: no current job" else null
            return@LaunchedEffect
        }
        if (!autoDialEnabled) return@LaunchedEffect

        val dispatchKey = "${job.jobId}:${job.lockToken}"
        if (lastAutoDialDispatchKey == dispatchKey) return@LaunchedEffect

        val slot = job.simSlot
        if (slot == null || (slot != 1 && slot != 2)) {
            autoDialStatusMsg = "Auto dial skipped: invalid sim_slot"
            return@LaunchedEffect
        }

        if (!hasPhoneStatePerm) {
            autoDialStatusMsg = "Auto dial skipped: phone permission missing"
            return@LaunchedEffect
        }

        val sim = simManager.getSimForSlot(slot)
        if (sim == null) {
            autoDialStatusMsg = "Auto dial skipped: SIM$slot not active"
            return@LaunchedEffect
        }

        if (!simLooksCorrect(slot, sim.carrierName)) {
            autoDialStatusMsg = "Auto dial skipped: SIM$slot not expected (${sim.carrierName})"
            return@LaunchedEffect
        }

        val net = job.network?.uppercase()
        if (net == "TIGO" && !yasSaved) {
            autoDialStatusMsg = "Auto dial skipped: Yas PIN not saved"
            return@LaunchedEffect
        }
        if ((net == "VODACOM" || net == "MPESA") && (!vodacomSaved || !assistantSaved)) {
            autoDialStatusMsg = "Auto dial skipped: Vodacom setup missing"
            return@LaunchedEffect
        }

        val ussd = ussdCodeForNetwork(job.network)

        openDialerWithUssd(ussd)
        UssdDialBus.request(job.jobId, ussd, slot)

        EngineWsManager.client?.sendEvent(
            job.jobId,
            "AUTO_DIAL_REQUESTED",
            mapOf("ussd" to ussd, "sim_slot" to slot)
        )

        autoDialStatusMsg = "Auto dial started: $ussd (SIM$slot)"
        lastAutoDialDispatchKey = dispatchKey
    }

    // Aggressive auto-timeout countdown + auto-fail once
    LaunchedEffect(jobId) {
        timeoutTriggered = false
        remainingSeconds = JOB_TIMEOUT_SECONDS

        if (jobId == null) return@LaunchedEffect

        val thisJobId = jobId
        while (remainingSeconds > 0) {
            delay(1_000)
            val latest = CurrentJobState.job.value
            if (latest == null || latest.jobId != thisJobId) return@LaunchedEffect
            remainingSeconds -= 1
        }

        val latest = CurrentJobState.job.value
        if (!timeoutTriggered && latest != null && latest.jobId == thisJobId) {
            timeoutTriggered = true
            EngineWsManager.client?.sendEvent(
                jobId = latest.jobId,
                eventType = "AUTO_TIMEOUT_TRIGGERED",
                payload = mapOf("timeout_seconds" to JOB_TIMEOUT_SECONDS)
            )
            EngineWsManager.client?.sendFailed(latest.jobId, latest.lockToken, "Operator timeout")
            EngineState.set("Auto-failed job (timeout) ❌")
            load()
        }
    }

    LaunchedEffect(Unit) { load() }

    val scrollState = rememberScrollState()

    @Composable
    fun StepRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Checkbox(checked = checked, onCheckedChange = { onToggle(it) })
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp)
    ) {
        // Engine start/stop
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { EngineController.start(appContext) }, modifier = Modifier.weight(1f)) {
                Text("Start Engine")
            }
            Button(onClick = { EngineController.stop(appContext) }, modifier = Modifier.weight(1f)) {
                Text("Stop Engine")
            }
        }

        Spacer(Modifier.height(12.dp))

        // Open Accessibility settings
        OutlinedButton(
            onClick = { openAccessibilitySettings() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enable USSD Accessibility")
        }

        Spacer(Modifier.height(10.dp))

        // Auto Dial toggle + status message
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Auto Dial (experimental)")
            Switch(checked = autoDialEnabled, onCheckedChange = { autoDialEnabled = it })
        }
        if (autoDialEnabled && autoDialStatusMsg != null) {
            Spacer(Modifier.height(6.dp))
            Text(autoDialStatusMsg!!, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        // USSD snapshot
        Text("USSD Window Text", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Source: ${ussdSnap.sourcePackage}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Text(ussdSnap.text)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { UssdAutomationBus.clear() }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear USSD Text")
        }

        Spacer(Modifier.height(14.dp))

        Text("Engine Status", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(engineMsg)

        Spacer(Modifier.height(14.dp))

        // Setup Status
        Text("Setup Status", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Yas PIN: ${if (yasSaved) "Saved ✅" else "Missing ❌"}")
        Text("Vodacom PIN: ${if (vodacomSaved) "Saved ✅" else "Missing ❌"}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Assistant ID: ${if (assistantSaved) "Saved ✅" else "Missing ❌"}")
            TextButton(onClick = { revealAssistantId = !revealAssistantId }, enabled = assistantSaved) {
                Text(if (revealAssistantId) "Hide" else "Show")
            }
        }
        if (assistantSaved && revealAssistantId) {
            Text("Assistant ID = $assistantId", style = MaterialTheme.typography.titleSmall)
        }

        Spacer(Modifier.height(14.dp))

        // SIM status panel
        Text("SIM Status", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (!hasPhoneStatePerm) {
            Text("Permission needed to read SIM slot mapping.")
            Spacer(Modifier.height(6.dp))
            Button(onClick = { phoneStatePermLauncher.launch(Manifest.permission.READ_PHONE_STATE) }) {
                Text("Grant SIM Permission")
            }
        } else {
            if (sims.isEmpty()) {
                Text("No active SIMs detected.")
            } else {
                sims.forEach { s ->
                    val ok = simLooksCorrect(s.slotIndex, s.carrierName)
                    Text("SIM${s.slotIndex}: ${s.carrierName} (${s.displayName})  ${if (ok) "✅" else "⚠️"}")
                }
                Spacer(Modifier.height(6.dp))
                Text("Expected: SIM1 = Yas, SIM2 = Vodacom Tanzania")
            }
        }

        Spacer(Modifier.height(14.dp))

        // Current job + session
        Text("Current Job", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))

        if (currentJob == null) {
            Text("None")
        } else {
            val job = currentJob!!
            Text("Job ID: ${job.jobId}")
            Text("Network: ${job.network ?: "unknown"}")
            Text("SIM Slot: ${job.simSlot?.toString() ?: "unknown"}")
            Text("Customer: ${job.msisdn ?: "unknown"}")
            Text("Amount: ${job.amount?.toString() ?: "unknown"}")

            Spacer(Modifier.height(8.dp))
            Text("Time remaining: ${remainingSeconds}s", style = MaterialTheme.typography.titleSmall)

            Spacer(Modifier.height(10.dp))

            val ussd = ussdCodeForNetwork(job.network)

            Text("Job Session Checklist", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))

            StepRow("SIM confirmed", simConfirmed) { v ->
                simConfirmed = v
                EngineWsManager.client?.sendEvent(job.jobId, "STEP_SIM_CONFIRMED", mapOf("value" to v))
            }

            StepRow("Customer number entered", enteredCustomer) { v ->
                enteredCustomer = v
                EngineWsManager.client?.sendEvent(job.jobId, "STEP_CUSTOMER_ENTERED", mapOf("value" to v))
            }

            StepRow("Amount entered", enteredAmount) { v ->
                enteredAmount = v
                EngineWsManager.client?.sendEvent(job.jobId, "STEP_AMOUNT_ENTERED", mapOf("value" to v))
            }

            if (job.network?.uppercase() == "VODACOM" || job.network?.uppercase() == "MPESA") {
                StepRow("Assistant ID entered", enteredAssistant) { v ->
                    enteredAssistant = v
                    EngineWsManager.client?.sendEvent(job.jobId, "STEP_ASSISTANT_ENTERED", mapOf("value" to v))
                }
            }

            StepRow("PIN entered", enteredPin) { v ->
                enteredPin = v
                EngineWsManager.client?.sendEvent(job.jobId, "STEP_PIN_ENTERED", mapOf("value" to v))
            }

            Spacer(Modifier.height(10.dp))

            if (dialError != null) {
                Text(dialError!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(6.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { beginSafeDialForJob() }, modifier = Modifier.weight(1f)) {
                    Text("Open Dialer (Safe)")
                }
                OutlinedButton(onClick = { CurrentJobState.set(null) }, modifier = Modifier.weight(1f)) {
                    Text("Clear Job")
                }
            }

            Spacer(Modifier.height(10.dp))

            Text("Failure reason (if needed):", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))

            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { failMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(selectedFailReason) }

                DropdownMenu(
                    expanded = failMenuExpanded,
                    onDismissRequest = { failMenuExpanded = false }
                ) {
                    failureReasons.forEach { r ->
                        DropdownMenuItem(
                            text = { Text(r) },
                            onClick = {
                                selectedFailReason = r
                                failMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        EngineWsManager.client?.sendEvent(job.jobId, "JOB_COMPLETED_CLICKED")
                        EngineWsManager.client?.sendSuccess(job.jobId, job.lockToken)
                        load()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Completed ✅") }

                Button(
                    onClick = {
                        EngineWsManager.client?.sendEvent(job.jobId, "JOB_FAILED_CLICKED", mapOf("reason" to selectedFailReason))
                        EngineWsManager.client?.sendFailed(job.jobId, job.lockToken, selectedFailReason)
                        load()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Failed ❌") }
            }

            Spacer(Modifier.height(10.dp))

            Text("Manual Steps:", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            when (job.network?.uppercase()) {
                "TIGO" -> {
                    Text("1) Dial $ussd")
                    Text("2) Choose: Deposit")
                    Text("3) Enter customer number")
                    Text("4) Enter amount")
                    Text("5) Confirm")
                    Text("6) Enter Yas PIN")
                    Text("IMPORTANT: Must use SIM Slot 1 (Yas)")
                }
                "VODACOM", "MPESA" -> {
                    Text("1) Dial $ussd")
                    Text("2) Choose: Deposit")
                    Text("3) Enter customer number")
                    Text("4) Enter amount")
                    Text("5) Enter Assistant ID")
                    Text("6) Confirm")
                    Text("7) Enter Vodacom PIN")
                    Text("IMPORTANT: Must use SIM Slot 2 (Vodacom Tanzania)")
                }
                else -> Text("Dial $ussd and follow prompts manually.")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Stats controls
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { load() }, enabled = !loading, modifier = Modifier.weight(1f)) {
                Text(if (loading) "Refreshing..." else "Refresh Stats")
            }
            OutlinedButton(onClick = onLogout, modifier = Modifier.weight(1f)) { Text("Logout") }
        }

        Spacer(Modifier.height(12.dp))

        if (errorMsg != null) {
            Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(10.dp))
        }

        val summary = summaryData
        if (summary != null) {
            Text("Job Status Counts", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            summary.stats.entries.sortedBy { it.key }.forEach { (status, count) ->
                Text("$status: $count")
            }

            Spacer(Modifier.height(16.dp))

            Text("Needs Review", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Count: ${summary.needs_review.size}")
            summary.needs_review.take(5).forEachIndexed { idx, item ->
                val jid = item["id"]?.toString() ?: "unknown"
                val lastError = item["last_error"]?.toString() ?: ""
                Text("${idx + 1}. $jid ${if (lastError.isNotBlank()) "- $lastError" else ""}")
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // Confirm SIM dialog
    if (showConfirmDial) {
        AlertDialog(
            onDismissRequest = { showConfirmDial = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ussd = dialUssd
                        val jid = dialJobId
                        if (ussd != null && jid != null) {
                            EngineState.set("Opening dialer for $ussd (SIM${dialSimSlot ?: "?"} ${dialSimCarrier ?: ""})")
                            openDialerWithUssd(ussd)

                            EngineWsManager.client?.sendEvent(
                                jobId = jid,
                                eventType = "DIALER_OPENED",
                                payload = mapOf(
                                    "ussd" to ussd,
                                    "sim_slot" to dialSimSlot,
                                    "carrier" to dialSimCarrier,
                                    "network" to dialNetwork
                                )
                            )
                        }
                        showConfirmDial = false
                    }
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showConfirmDial = false }) { Text("Cancel") } },
            title = { Text("Confirm SIM") },
            text = {
                Text(
                    "This job MUST be dialed using:\n\n" +
                            "SIM Slot ${dialSimSlot ?: "?"} (${dialSimCarrier ?: "Unknown"})\n\n" +
                            "Proceed?"
                )
            }
        )
    }
}