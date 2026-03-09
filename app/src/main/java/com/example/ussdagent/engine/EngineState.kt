package com.example.ussdagent.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object EngineState {
    private val _lastMessage = MutableStateFlow("Engine idle")
    val lastMessage: StateFlow<String> = _lastMessage

    fun set(msg: String) {
        _lastMessage.value = msg
    }
}