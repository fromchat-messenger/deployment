package ru.fromchat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppForeground {
    private val _isInForeground = MutableStateFlow(true)
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    fun setForeground(inForeground: Boolean) {
        _isInForeground.value = inForeground
    }
}
