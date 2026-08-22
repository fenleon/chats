package com.lightphone.chats.server

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Permission trampoline for POST_NOTIFICATIONS (2026-08-22): with
 * targetSdk 33+ declaring the permission in the manifest is not enough — it
 * stays denied (and `NotificationManager.areNotificationsEnabled()` false)
 * until something requests it at runtime, and nothing did: message
 * notifications were silently blocked on every fresh install ("I do not
 * receive notifications"). The tool runtime forbids permission requests (same
 * ban as startActivity — see [VoiceNoteActivity]), so the tool starts this
 * translucent activity via `startServerActivity` the first time the chat list
 * shows with a logged-in account. Finishes immediately when the permission is
 * already granted (invisible re-entry) and on either dialog result — a denial
 * needs no error surface, ChatNotifier already skips posting while
 * notifications are off, and Android itself stops re-prompting after repeated
 * denials.
 */
class NotificationPermissionActivity : ComponentActivity() {

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
