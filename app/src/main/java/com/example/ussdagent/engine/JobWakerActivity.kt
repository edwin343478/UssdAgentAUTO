package com.example.ussdagent.engine

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.example.ussdagent.ussd.UssdDialBus

class JobWakerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake + keep screen on (Activity-level is reliable)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        setTurnScreenOn(true)
        setShowWhenLocked(true)

        val jobId = intent.getStringExtra(EXTRA_JOB_ID)
        val ussd = intent.getStringExtra(EXTRA_USSD)
        val simSlot = intent.getIntExtra(EXTRA_SIM_SLOT, -1)

        if (!jobId.isNullOrBlank() && !ussd.isNullOrBlank() && (simSlot == 1 || simSlot == 2)) {
            // Tell your auto-dial system which SIM to use for this job
            UssdDialBus.request(jobId, ussd, simSlot)

            // Open dialer for the USSD (keeps your working accessibility auto-call + SIM selection)
            val tel = "tel:" + ussd.replace("#", "%23")
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse(tel)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(dialIntent)
        }

        // Finish quickly so Dialer can take over
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 600)
    }

    companion object {
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_USSD = "ussd"
        const val EXTRA_SIM_SLOT = "sim_slot"
    }
}