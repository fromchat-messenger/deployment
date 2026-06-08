package ru.fromchat.api.instance

import ru.fromchat.config.ServerConfigData

fun ServerConfigData.configKey(): String {
    val scheme = if (httpsEnabled) "1" else "0"
    return "${serverIp.trim().lowercase()}|$apiPort|$scheme"
}
