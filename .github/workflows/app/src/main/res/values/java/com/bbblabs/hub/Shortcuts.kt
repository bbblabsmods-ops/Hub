package com.bbblabs.hub

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings

/** One settings shortcut: a label plus intents to try in order until one opens. */
class Shortcut(val label: String, val tries: List<Intent>)

private fun action(a: String) = Intent(a)

private fun component(pkg: String, cls: String) =
    Intent().setComponent(ComponentName(pkg, cls))

fun settingsShortcuts(): List<Shortcut> {
    val s = "com.android.settings"
    return listOf(
        Shortcut("Android Settings", listOf(action(Settings.ACTION_SETTINGS))),
        Shortcut(
            "Developer Options",
            listOf(
                action(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                component(s, "$s.Settings\$DevelopmentSettingsDashboardActivity")
            )
        ),
        Shortcut(
            "Wireless Debugging",
            listOf(
                component(s, "$s.Settings\$WirelessDebuggingActivity"),
                component(s, "$s.Settings\$DevelopmentSettingsDashboardActivity"),
                action(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            )
        ),
        Shortcut("About Phone", listOf(action(Settings.ACTION_DEVICE_INFO_SETTINGS))),
        Shortcut("Wi-Fi", listOf(action(Settings.ACTION_WIFI_SETTINGS))),
        Shortcut("Internet / Network", listOf(action(Settings.ACTION_WIRELESS_SETTINGS))),
        Shortcut("Bluetooth", listOf(action(Settings.ACTION_BLUETOOTH_SETTINGS))),
        Shortcut("Apps", listOf(action(Settings.ACTION_APPLICATION_SETTINGS))),
        Shortcut("All Apps", listOf(action(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS))),
        Shortcut("Default Apps", listOf(action(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))),
        Shortcut("Display", listOf(action(Settings.ACTION_DISPLAY_SETTINGS))),
        Shortcut("Sound", listOf(action(Settings.ACTION_SOUND_SETTINGS))),
        Shortcut("Battery Saver", listOf(action(Settings.ACTION_BATTERY_SAVER_SETTINGS))),
        Shortcut("Storage", listOf(action(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))),
        Shortcut("Location", listOf(action(Settings.ACTION_LOCATION_SOURCE_SETTINGS))),
        Shortcut("Security", listOf(action(Settings.ACTION_SECURITY_SETTINGS))),
        Shortcut("Accessibility", listOf(action(Settings.ACTION_ACCESSIBILITY_SETTINGS))),
        Shortcut("Date & Time", listOf(action(Settings.ACTION_DATE_SETTINGS))),
        Shortcut("Language", listOf(action(Settings.ACTION_LOCALE_SETTINGS))),
        Shortcut("Notifications", listOf(action("android.settings.NOTIFICATION_SETTINGS"))),
        Shortcut("Draw Over Other Apps", listOf(action(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)))
    )
}
