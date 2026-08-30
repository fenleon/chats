package com.lightphone.chats.server

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import com.thelightphone.sdk.server.ClientCertType
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.LightSdkServer
import org.unifiedpush.android.connector.UnifiedPush
import java.security.MessageDigest

// SHA-256 fingerprint of sdk/keys/lightsdk-dev.jks (alias: lightsdk-dev). Any
// APK signed with the workspace dev key is treated as Light-SDK-signed.
private const val LIGHTSDK_DEV_CERT_SHA256 =
    "B9C33E29B0CCAD2BFF11ACAB55F65A3C517EF4BC92CD9C77785366FA353D5F28"

private const val TAG = "ServerBootstrap"

// VAPID public key from the mollysocket link LightOS returned for this device
// (2026-08-21 probe): mollysocket://link?vapid=...&url=...&type=webserver. The
// UP connector's own generated key fails its regex (3.3.2), so pass an
// explicit format-valid key. Removed with the probe.
private const val MOLLYSOCKET_VAPID =
    "BJpWPefLMOy_hvZsejTdQpRfvjoirNwVjhdjPo1nNPdcwQQnoANsHQlQdg_vSBqsvHY-4t_KqyFDzsuYACNuGTw"

/**
 * Single-APK build (2026-08-19): the former companion's `ServerApplication`
 * wiring runs here, inside the merged tool APK. A ContentProvider is the only
 * app-start hook with a real [Context] that is not part of the tool-plugin
 * scanned module — it wires the SDK server (settings, cert check, chat
 * methods) and hands the application context to [MatrixRepository.init]
 * (session restore + sync service start). Providers run before the tool's
 * first binder call, so the tool binds to its own APK's LightSdkService and
 * the server is already wired when it does.
 */
class ServerBootstrapProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context?.applicationContext ?: return false
        with(LightSdkServer) {
            defaultClientFilterLevel = ClientFilterLevel.AllowLightSignedApks
            // Haptics answer from LightOS's real setting via the relay (PLATFORM-RELAY).
            provideSdkSettings = { RelaySdkServerSettings(it) }
            checkCert = { callingPackage -> checkLightSdkCert(context, callingPackage) }
            customServiceMethodResolver = { callingId, methodId, payload ->
                ChatServiceMethods.dispatch(methodId, payload)
            }
            // Hardware keys: while a voice note is playing/paused, the volume
            // rocker adjusts the media stream here (one step per press) — the
            // tool's in-app volume panel replica mirrors it, and the native
            // LightOS panel is ringer-only for third-party tools, so volume
            // must NOT be relayed then (feedback 2026-08-30). Otherwise every
            // event goes to LightOS (volume panel, brightness wheel, camera).
            onDeviceKeyEvent = { _, event ->
                val volumeKeyDown = event.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                        event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                // isThreadOnScreen: only while a thread is open — from the list
                // there's no panel to mirror the change, so don't silently
                // adjust (or swallow the key) then.
                if (volumeKeyDown && MatrixRepository.isVoiceNoteActive() &&
                    MatrixRepository.isThreadOnScreen()
                ) {
                    if ((event.repeatCount ?: 0) == 0) { // one step per press; drop auto-repeat
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_VOLUME_UP -> audio.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0,
                            )
                            KeyEvent.KEYCODE_VOLUME_DOWN -> audio.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0,
                            )
                        }
                    }
                } else {
                    // Every other event goes to LightOS unchanged. The SDK's
                    // LightActivity forwards ALL actions (DOWN/UP/MULTIPLE) for
                    // mapped keys — incl. the shutter (27/80) — with
                    // componentToRelaunch, and LightOS is the handler. The
                    // 2026-08-23 "relay shutter DOWN only" experiment deviated
                    // from that contract and LP3-tested unchanged (2026-08-30:
                    // camera still opened hidden, colour flipped, stale camera
                    // on tool exit) — removed to match the SDK flow exactly.
                    PlatformRelay.sendDeviceKeyEvent(event)
                    // Camera key: the relay opens LightOS's camera, but a
                    // relayed event never foregrounds com.lightos (the real key
                    // is captured in MainActivity.onKeyDown with deviceId=2 /
                    // source=0x101 and the camera screen opens visibly; the
                    // relayed one mounts the camera hidden and flips greyscale
                    // with nothing on screen). Launch HOME so com.lightos (the
                    // home app) comes to front with the camera screen already
                    // open, matching the toolbox-key behaviour (LP3 2026-08-30).
                    if (event.keyCode == KeyEvent.KEYCODE_CAMERA &&
                        event.action == KeyEvent.ACTION_DOWN && (event.repeatCount ?: 0) == 0
                    ) {
                        context.startActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            // Runtime permission flow (audit 2026-08-23): the tool requests
            // POST_NOTIFICATIONS through the SDK flow; this APK hosts the AOSP
            // dialog activity (ChatsPermissionActivity) and adds the
            // notification permission to the grantable set.
            permissionActivity = ChatsPermissionActivity::class.java
            androidPermissionAllowed = { _, permissionName ->
                permissionName in setOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
        // Relay hardware keys + user preferences to LightOS (PLATFORM-RELAY).
        PlatformRelay.bind(context)
        // Dev probe: register a UP app instance against LightOS's distributor
        // to capture the standard push endpoint (the "other socket" vs the
        // mollysocket URI). Delayed so it runs after the SDK's Application
        // startup (which re-saves its own serverPackage as the distributor);
        // the connector only persists the app-side token when the distributor
        // is already settled. Removed with the probe.
        Thread {
            Thread.sleep(5000)
            UnifiedPush.saveDistributor(context, "com.lightos")
            UnifiedPush.register(
                context,
                instance = "light-push",
                vapid = MOLLYSOCKET_VAPID,
                messageForDistributor = "com.lightphone.chats",
            )
        }.start()
        // Restores a stored session (if any) and starts the sync service.
        MatrixRepository.init(context)
        return true
    }

    private fun checkLightSdkCert(context: Context, callingPackage: String): ClientCertType {
        val info = try {
            context.packageManager.getPackageInfo(callingPackage, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            return ClientCertType.Unknown
        }
        val signingInfo = info.signingInfo ?: return ClientCertType.Unknown
        val signers: Array<Signature> = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        val md = MessageDigest.getInstance("SHA-256")
        val matches = signers.any { sig ->
            md.digest(sig.toByteArray()).toHexString()
                .equals(LIGHTSDK_DEV_CERT_SHA256, ignoreCase = true)
        }
        return if (matches) ClientCertType.LightSdkSignedUnverified else ClientCertType.Unknown
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }

    // The provider exists for its onCreate only; no content is served.
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
