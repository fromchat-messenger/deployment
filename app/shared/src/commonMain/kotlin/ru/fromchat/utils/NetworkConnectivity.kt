package ru.fromchat.utils

import kotlinx.coroutines.flow.StateFlow

expect object NetworkConnectivity {
    val isOnline: StateFlow<Boolean>

    fun ensureStarted()
}