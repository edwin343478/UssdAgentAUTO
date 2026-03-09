package com.example.ussdagent.ussd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class UssdSnapshot(
    val sourcePackage: String,
    val text: String
)

object UssdAutomationBus {
    private val _snapshot = MutableStateFlow(
        UssdSnapshot(sourcePackage = "-", text = "(no ussd yet)")
    )
    val snapshot: StateFlow<UssdSnapshot> = _snapshot

    fun publish(sourcePackage: String, text: String) {
        _snapshot.value = UssdSnapshot(sourcePackage = sourcePackage, text = text)
    }

    fun clear() {
        _snapshot.value = UssdSnapshot(sourcePackage = "-", text = "(cleared)")
    }
}