package ru.fromchat.ui.main.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import ru.fromchat.Res
import ru.fromchat.settings
import ru.fromchat.ui.LocalNavController
import ru.fromchat.ui.main.TabBase

@Composable
fun SettingsTab() {
    TabBase {
        val nav = LocalNavController.current
        SettingsHubScreen(
            onAppearance = { nav.navigate(SettingsRoutes.Appearance) },
            onServerTools = { nav.navigate(SettingsRoutes.ServerTools) },
            onNotifications = { nav.navigate(SettingsRoutes.Notifications) },
            onDevices = { nav.navigate(SettingsRoutes.Devices) },
            onSecurity = { nav.navigate(SettingsRoutes.Security) },
            onAccount = { nav.navigate(SettingsRoutes.Account) },
            onAbout = { nav.navigate("about") },
            title = stringResource(Res.string.settings),
            modifier = Modifier.fillMaxSize()
        )
    }
}
