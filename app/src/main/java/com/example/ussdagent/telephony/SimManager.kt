package com.example.ussdagent.telephony

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

data class SimSlotInfo(
    val slotIndex: Int,        // 1 or 2 (your convention)
    val subscriptionId: Int,
    val displayName: String,
    val carrierName: String
)

class SimManager(private val context: Context) {

    fun getActiveSims(): List<SimSlotInfo> {
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()

        val subs: List<SubscriptionInfo> = try {
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        return subs.map {
            SimSlotInfo(
                slotIndex = it.simSlotIndex + 1, // convert 0-based to 1/2
                subscriptionId = it.subscriptionId,
                displayName = it.displayName?.toString() ?: "SIM",
                carrierName = it.carrierName?.toString() ?: "Carrier"
            )
        }.sortedBy { it.slotIndex }
    }

    fun getSimForSlot(slot: Int): SimSlotInfo? {
        return getActiveSims().firstOrNull { it.slotIndex == slot }
    }
}