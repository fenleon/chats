package com.lightphone.chats.server

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.thelightphone.sdk.shared.LightServiceMethod

/**
 * AOSP runtime-permission host for the SDK's permission-request flow (wired as
 * [com.thelightphone.sdk.server.LightSdkServer.permissionActivity] in
 * [ServerBootstrapProvider]). The SDK's own emulator activity grants via
 * system-uid reflection — impossible for a regular app on the LP3 — so this
 * one asks the standard way and the user grants in the system dialog. Reads
 * the permission the tool requested from
 * [LightServiceMethod.RequestPermissionComponent.PERMISSION_NAME_KEY].
 */
class ChatsPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permission = intent.getStringExtra(
            LightServiceMethod.RequestPermissionComponent.PERMISSION_NAME_KEY
        ) ?: Manifest.permission.POST_NOTIFICATIONS
        Log.d(TAG, "requesting runtime permission: $permission")
        requestPermissions(arrayOf(permission), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(
            TAG,
            "permission result: granted=" +
                (requestCode == REQUEST_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED),
        )
        finish()
    }

    private companion object {
        const val TAG = "ChatsPermissionActivity"
        const val REQUEST_CODE = 1
    }
}
