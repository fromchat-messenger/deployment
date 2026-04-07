package ru.fromchat.ui.main.settings

/** Root [androidx.navigation.NavController] routes for full-screen settings (slide transitions). */
object SettingsRoutes {
    const val Appearance = "settings/appearance"
    const val ServerTools = "settings/server_tools"
    const val Notifications = "settings/notifications"
    const val Devices = "settings/devices"
    /** Hub with a single action to start the password flow. */
    const val Security = "settings/security"
    /** Single destination: in-screen steps + morphing hero (no nested nav routes per step). */
    const val SecurityPasswordFlow = "settings/security/password"
    const val Account = "settings/account"
}
