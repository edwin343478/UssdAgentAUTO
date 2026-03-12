package com.example.ussdagent.ussd

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ussdagent.data.store.SecureStore
import com.example.ussdagent.engine.CurrentJobState
import com.example.ussdagent.engine.EngineState
import com.example.ussdagent.engine.ws.EngineWsManager
import com.example.ussdagent.telephony.SimManager

class UssdAccessibilityService : AccessibilityService() {

    private val SESSION_TIMEOUT_MS = 4 * 60_000L
    private val STEP_RETRY_DELAY_MS = 2_500L
    private val MAX_STEP_RETRIES = 2
    private val PUBLISH_THROTTLE_MS = 250L

    private var lastPublishedText: String = ""
    private var lastPublishedAtMs: Long = 0L

    data class SessionState(
        val jobId: String,
        val startedAtMs: Long,
        var pendingStep: String? = null,
        var pendingScreenHash: Int? = null,
        var pendingValueTag: String? = null,
        var lastActionAtMs: Long = 0L,
        var retries: Int = 0
    )

    private var session: SessionState? = null

    private val allowedPackages = setOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.app.telephonyui"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        UssdAutomationBus.publish("service", "Accessibility connected ✅ (waiting for USSD)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val srcPkg = event.packageName?.toString() ?: return

        if (srcPkg == packageName) return

        val className = event.className?.toString()?.lowercase() ?: ""
        val looksDialogLike = className.contains("dialog") || className.contains("alert") || className.contains("ussd")
        if (srcPkg !in allowedPackages && !looksDialogLike) return

        val root = windows
            ?.asSequence()
            ?.mapNotNull { it.root }
            ?.firstOrNull { it.packageName?.toString() == srcPkg }
            ?: run {
                val r = rootInActiveWindow
                if (r?.packageName?.toString() == srcPkg) r else null
            }
            ?: return

        val text = extractAllText(root)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .take(2500)

        if (text.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        if (text != lastPublishedText && (now - lastPublishedAtMs) > PUBLISH_THROTTLE_MS) {
            lastPublishedText = text
            lastPublishedAtMs = now
            UssdAutomationBus.publish(srcPkg, text)
        }

        handleAutoDial(root, srcPkg, text)
        autoDrive(root, text)
    }

    override fun onInterrupt() {}

    private fun handleAutoDial(root: AccessibilityNodeInfo, srcPkg: String, screenText: String) {
        val job = CurrentJobState.job.value ?: return
        val req = UssdDialBus.req.value ?: return
        if (req.jobId != job.jobId) return

        val t = screenText.lowercase()
        val ussdStarted =
            t.contains("mixx by yas") || t.contains("m-pesa") ||
                    t.contains("ingiza") || t.contains("weka namba ya simu") ||
                    t.contains("utambulisho wa msaidizi") || looksLikePinPrompt(t)

        if (ussdStarted) {
            UssdDialBus.clear()
            return
        }

        val looksLikeSimPicker =
            t.contains("sim1") || t.contains("sim 1") || t.contains("sim2") || t.contains("sim 2") ||
                    t.contains("yas") || t.contains("vodacom") ||
                    t.contains("just once") || t.contains("always") || t.contains("mara moja")

        if (looksLikeSimPicker && !req.simChosen) {
            val ok = chooseSimFromPicker(root, req.simSlot)
            if (ok) {
                UssdDialBus.markSimChosen()
                clickJustOnceIfPresent(root)
            }
            return
        }

        val isDialer = srcPkg.contains("dialer")
        if (isDialer && !req.callClicked) {
            val ok =
                clickSimSpecificCallButton(root, req.simSlot) ||
                        clickCallButton(root)

            if (ok) UssdDialBus.markCallClicked()
        }
    }

    private fun clickSimSpecificCallButton(root: AccessibilityNodeInfo, simSlot: Int): Boolean {
        val want = if (simSlot == 1) "sim1" else "sim2"

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)

        val btn = nodes.firstOrNull { n ->
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            val cd = n.contentDescription?.toString()?.lowercase().orEmpty()
            val txt = n.text?.toString()?.lowercase().orEmpty()

            val simMatch = id.contains(want) || cd.contains(want) || txt.contains(want)
            val callish = id.contains("dial") || id.contains("call") || cd.contains("call") || cd.contains("dial")
            simMatch && (n.isClickable || callish)
        } ?: return false

        return clickNodeOrClickableParent(btn)
    }

    private fun clickJustOnceIfPresent(root: AccessibilityNodeInfo) {
        clickByLabels(root, listOf("just once", "once", "mara moja", "mara 1"))
    }

    private fun clickCallButton(root: AccessibilityNodeInfo): Boolean {
        val ids = listOf(
            "com.android.dialer:id/dialpad_floating_action_button",
            "com.google.android.dialer:id/dialpad_floating_action_button",
            "com.samsung.android.dialer:id/dialButton",
            "com.samsung.android.dialer:id/dialpad_floating_action_button"
        )

        for (vid in ids) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(vid)
                val n = nodes?.firstOrNull() ?: continue
                return clickNodeOrClickableParent(n)
            } catch (_: Throwable) {}
        }

        return clickByLabels(root, listOf("call", "dial", "piga", "iga"))
    }

    private fun chooseSimFromPicker(root: AccessibilityNodeInfo, simSlot: Int): Boolean {
        val simManager = SimManager(applicationContext)
        val sim = simManager.getSimForSlot(simSlot)

        val tokens = mutableListOf<String>()

        tokens += "sim$simSlot"
        tokens += "sim $simSlot"
        tokens += "slot $simSlot"

        val carrier = sim?.carrierName?.lowercase()?.trim().orEmpty()
        if (carrier.isNotBlank()) {
            tokens += carrier
            tokens += carrier.split(" ", "-", "_").filter { it.length >= 3 }
        }

        if (simSlot == 1) tokens += listOf("yas", "tigo", "mixx")
        if (simSlot == 2) tokens += listOf("vodacom", "m-pesa", "mpesa")

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)

        val choice = nodes.firstOrNull { n ->
            val txt = n.text?.toString()?.lowercase()?.trim().orEmpty()
            val cd = n.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
            tokens.any { key -> txt.contains(key) || cd.contains(key) }
        } ?: return false

        return clickNodeOrClickableParent(choice)
    }

    private fun autoDrive(root: AccessibilityNodeInfo, screenText: String) {
        val job = CurrentJobState.job.value ?: run {
            session = null
            return
        }

        val now = SystemClock.elapsedRealtime()

        if (session?.jobId != job.jobId) {
            session = SessionState(jobId = job.jobId, startedAtMs = now)
            EngineWsManager.client?.sendEvent(job.jobId, "USSD_SESSION_STARTED")
        }

        val s = session ?: return

        if ((now - s.startedAtMs) > SESSION_TIMEOUT_MS) {
            if (shouldAct(s, "SESSION_TIMEOUT", screenText, now)) {
                EngineWsManager.client?.sendEvent(job.jobId, "USSD_SESSION_TIMEOUT")
                clickNegative(root)
                EngineWsManager.client?.sendFailed(job.jobId, job.lockToken, "USSD session timeout")
            }
            return
        }

        val net = job.network?.trim()?.uppercase() ?: return
        val isTigo = net == "TIGO"
        val isMpesa = (net == "VODACOM" || net == "MPESA")

        val msisdn = job.msisdn?.trim()?.filter { it.isDigit() } ?: return
        val amount = job.amount?.toString() ?: return

        val store = SecureStore(applicationContext)
        val assistantId = store.getMpesaAssistantId()?.trim()?.uppercase()
        val yasPin = store.getYasPin()?.trim()
        val vodaPin = store.getVodacomPin()?.trim()

        val t = screenText.lowercase()
        val screenHash = screenText.hashCode()

        if (s.pendingScreenHash != null && s.pendingScreenHash != screenHash) {
            s.pendingStep = null
            s.pendingScreenHash = null
            s.pendingValueTag = null
            s.retries = 0
        }

        if (looksLikeSuccess(t, isTigo, isMpesa)) {
            if (shouldAct(s, "SUCCESS", screenText, now)) {
                EngineWsManager.client?.sendEvent(job.jobId, "USSD_SUCCESS_DETECTED")

                val client = EngineWsManager.client
                if (client != null) {
                    val sent = client.sendSuccess(job.jobId, job.lockToken)
                    if (!sent) {
                        EngineState.set("USSD succeeded but ack failed - will clear locally")
                        Handler(Looper.getMainLooper()).postDelayed({
                            CurrentJobState.set(null)
                        }, 2000)
                    }
                } else {
                    EngineState.set("WebSocket not connected - clearing job locally")
                    CurrentJobState.set(null)
                }
                clickPositive(root)
            }
            return
        }

        val failReason = extractFailureReason(t)
        if (failReason != null) {
            if (shouldAct(s, "FAILED", screenText, now)) {
                EngineWsManager.client?.sendEvent(job.jobId, "USSD_FAILED_DETECTED", mapOf("reason" to failReason))

                val client = EngineWsManager.client
                if (client != null) {
                    val sent = client.sendFailed(job.jobId, job.lockToken, failReason)
                    if (!sent) {
                        EngineState.set("USSD failed but ack failed - will clear locally")
                        Handler(Looper.getMainLooper()).postDelayed({
                            CurrentJobState.set(null)
                        }, 2000)
                    }
                } else {
                    EngineState.set("WebSocket not connected - clearing job locally")
                    CurrentJobState.set(null)
                }
                clickPositive(root)
            }
            return
        }

        if (s.pendingStep != null && s.pendingScreenHash == screenHash) {
            if ((now - s.lastActionAtMs) > STEP_RETRY_DELAY_MS) {
                if (s.retries < MAX_STEP_RETRIES) {
                    s.retries += 1
                    s.lastActionAtMs = now
                    clickPositive(root)
                    EngineWsManager.client?.sendEvent(
                        job.jobId,
                        "USSD_STEP_RETRY",
                        mapOf("step" to s.pendingStep!!, "retry" to s.retries)
                    )
                } else {
                    EngineWsManager.client?.sendEvent(job.jobId, "USSD_STEP_STUCK", mapOf("step" to s.pendingStep!!))
                    clickNegative(root)
                    EngineWsManager.client?.sendFailed(job.jobId, job.lockToken, "USSD stuck at ${s.pendingStep}")
                }
            }
            return
        }

        if (isTigo && t.contains("mixx by yas") && t.contains("weka pesa")) {
            performStep(s, job.jobId, root, screenHash, "TIGO_MENU", "MENU_CHOICE", "3", now)
            return
        }
        if (isMpesa && t.contains("m-pesa") && t.contains("kuweka pesa")) {
            performStep(s, job.jobId, root, screenHash, "MPESA_MENU", "MENU_CHOICE", "1", now)
            return
        }

        if (isTigo && t.contains("ingiza namba ya simu ya mteja")) {
            performStep(s, job.jobId, root, screenHash, "TIGO_MSISDN", "MSISDN", msisdn, now)
            return
        }
        if (isMpesa && t.contains("weka namba ya simu")) {
            performStep(s, job.jobId, root, screenHash, "MPESA_MSISDN", "MSISDN", msisdn, now)
            return
        }

        if (isTigo && t.contains("ingiza kiasi")) {
            performStep(s, job.jobId, root, screenHash, "TIGO_AMOUNT", "AMOUNT", amount, now)
            return
        }
        if (isMpesa && t.contains("weka kiasi")) {
            performStep(s, job.jobId, root, screenHash, "MPESA_AMOUNT", "AMOUNT", amount, now)
            return
        }

        if (isMpesa && t.contains("utambulisho wa msaidizi")) {
            if (!assistantId.isNullOrBlank()) {
                performStep(s, job.jobId, root, screenHash, "MPESA_ASSIST", "ASSISTANT_ID", assistantId, now)
            }
            return
        }

        if (looksLikePinPrompt(t)) {
            val pin = if (isTigo) yasPin else if (isMpesa) vodaPin else null
            if (!pin.isNullOrBlank()) {
                performStep(s, job.jobId, root, screenHash, "PIN", "PIN", pin, now)
            }
            return
        }
    }

    private fun performStep(
        s: SessionState,
        jobId: String,
        root: AccessibilityNodeInfo,
        screenHash: Int,
        stepName: String,
        valueTag: String,
        value: String,
        now: Long
    ) {
        val ok = setTextInAnyInput(root, value)
        if (!ok) return

        val clicked = clickPositive(root)
        if (!clicked) clickByLabels(root, listOf("send", "tuma", "endelea", "confirm", "ok", "sawa"))

        s.pendingStep = stepName
        s.pendingScreenHash = screenHash
        s.pendingValueTag = valueTag
        s.lastActionAtMs = now
        s.retries = 0

        val payload = when (valueTag) {
            "PIN" -> mapOf("step" to stepName, "value" to "***")
            else -> mapOf("step" to stepName)
        }
        EngineWsManager.client?.sendEvent(jobId, "USSD_STEP_SENT", payload)
    }

    private fun shouldAct(s: SessionState, step: String, screenText: String, now: Long): Boolean {
        val key = "${s.jobId}|$step|${screenText.hashCode()}"
        if (s.pendingStep == key && (now - s.lastActionAtMs) < 1500) return false
        s.pendingStep = key
        s.lastActionAtMs = now
        return true
    }

    private fun looksLikePinPrompt(t: String): Boolean {
        return t.contains("namba ya siri") ||
                (t.contains("weka") && t.contains("siri")) ||
                t.contains("pin") ||
                t.contains("kuhakiki") ||
                t.contains("kuthibitisha") ||
                t.contains("kubatilisha")
    }

    private fun looksLikeSuccess(t: String, isTigo: Boolean, isMpesa: Boolean): Boolean {
        if (isMpesa && t.contains("ombi lako limetumwa")) return true
        if (t.contains("limefanikiwa") || t.contains("imefanikiwa") || t.contains("successful")) return true
        if (t.contains("kumbukumbu")) return true
        return false
    }

    private fun extractFailureReason(t: String): String? {
        if (t.contains("haijafanikiwa")) return "USSD failed"
        if (t.contains("imekataliwa") || t.contains("imekataa")) return "USSD rejected"
        if (t.contains("sio sahihi") || t.contains("umesitishwa")) return "USSD rejected"
        if (t.contains("wrong pin") || (t.contains("siri") && t.contains("si sahihi"))) return "Wrong PIN"
        if (t.contains("hakuna") && t.contains("salio")) return "Insufficient balance"
        if (t.contains("huduma") && (t.contains("haipatikani") || t.contains("imekatika"))) return "Service unavailable"
        if (t.contains("network") && t.contains("busy")) return "Network busy"
        if (t.contains("timeout")) return "USSD timeout"
        return null
    }

    private fun clickPositive(root: AccessibilityNodeInfo): Boolean {
        val idCandidates = listOf("android:id/button1", "com.android.phone:id/button1")
        for (vid in idCandidates) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(vid)
                val n = nodes?.firstOrNull() ?: continue
                return clickNodeOrClickableParent(n)
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun clickNegative(root: AccessibilityNodeInfo): Boolean {
        val idCandidates = listOf("android:id/button2", "com.android.phone:id/button2")
        for (vid in idCandidates) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(vid)
                val n = nodes?.firstOrNull() ?: continue
                return clickNodeOrClickableParent(n)
            } catch (_: Throwable) {}
        }
        return clickByLabels(root, listOf("cancel", "ghairi", "futa"))
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        var clickNode: AccessibilityNodeInfo? = node
        var safety = 0
        while (clickNode != null && !clickNode.isClickable && safety < 8) {
            clickNode = clickNode.parent
            safety++
        }
        return clickNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun clickByLabels(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val btn = nodes.firstOrNull { n ->
            val txt = n.text?.toString()?.lowercase()?.trim().orEmpty()
            val cd = n.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
            labels.any { l -> txt == l || cd == l || txt.contains(l) || cd.contains(l) }
        } ?: return false
        return clickNodeOrClickableParent(btn)
    }

    private fun setTextInAnyInput(root: AccessibilityNodeInfo, value: String): Boolean {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        val idCandidates = listOf(
            "android:id/input",
            "com.android.phone:id/input",
            "com.android.dialer:id/input"
        )
        for (vid in idCandidates) {
            try {
                val found = root.findAccessibilityNodeInfosByViewId(vid)
                if (!found.isNullOrEmpty()) candidates.addAll(found)
            } catch (_: Throwable) {}
        }

        if (candidates.isEmpty()) candidates.addAll(findSetTextNodes(root))

        val target = candidates.firstOrNull { it.isEditable } ?: candidates.firstOrNull() ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findSetTextNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val supportsSetText = node.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } == true
        if (node.isEditable || supportsSetText) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            out.addAll(findSetTextNodes(child))
        }
        return out
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(node)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectAllNodes(c, out)
        }
    }

    private fun extractAllText(node: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        node.text?.toString()?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            out.addAll(extractAllText(child))
        }
        return out
    }
}