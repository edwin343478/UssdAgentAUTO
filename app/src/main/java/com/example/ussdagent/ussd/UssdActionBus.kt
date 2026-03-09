package com.example.ussdagent.ussd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class UssdAction {
    data class EnterPinAndSend(val pin: String) : UssdAction()
    object None : UssdAction()
}

object UssdActionBus {
    private val _action = MutableStateFlow<UssdAction>(UssdAction.None)
    val action: StateFlow<UssdAction> = _action

    fun request(action: UssdAction) { _action.value = action }
    fun clear() { _action.value = UssdAction.None }
}