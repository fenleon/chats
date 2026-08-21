package com.lightphone.chats.server

import android.content.Context
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.DefaultLightSdkServerSettings
import com.thelightphone.sdk.server.ForceFocusLevel
import com.thelightphone.sdk.server.LightSdkServerSettings
import com.thelightphone.sdk.shared.LightServiceMethod

/**
 * Default settings, except user preferences (haptics) answered from LightOS via
 * [PlatformRelay] so the tool's `lightClickable` reads the real device setting.
 * Falls back to the local default when the platform server is unreachable.
 */
class RelaySdkServerSettings(context: Context) : LightSdkServerSettings {
    private val defaults = DefaultLightSdkServerSettings(context)

    override var clientFilterLevel: ClientFilterLevel
        get() = defaults.clientFilterLevel
        set(value) { defaults.clientFilterLevel = value }

    override var forceFocusLevel: ForceFocusLevel
        get() = defaults.forceFocusLevel
        set(value) { defaults.forceFocusLevel = value }

    override var keyboardOptions: LightServiceMethod.GetKeyboardOptions.Response
        get() = defaults.keyboardOptions
        set(value) { defaults.keyboardOptions = value }

    override var userPreferences: LightServiceMethod.GetUserPreferences.Response
        get() = PlatformRelay.getUserPreferences() ?: defaults.userPreferences
        set(value) { defaults.userPreferences = value }
}
