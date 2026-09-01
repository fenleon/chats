package com.lightphone.chats.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.thelightphone.sdk.shared.LightConstants
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod

/**
 * Minimal passthrough client to the platform SDK server. Self-serving apps host
 * their own LightSdkService, so hardware keys and user preferences never reach
 * LightOS (v572 forwards keys only to the tool's serverPackage). Relay the
 * standard RPCs to com.lightos: DeviceKeyEvent → LightOS's volume panel /
 * brightness wheel / camera, GetUserPreferences → the real haptics setting.
 *
 * Emulator: point [PLATFORM] at "com.thelightphone.sdk.emulator".
 */
object PlatformRelay {

    private const val TAG = "PlatformRelay"
    private const val PLATFORM = "com.lightos" // emulator: com.thelightphone.sdk.emulator

    @Volatile private var binder: IBinder? = null
    @Volatile private var token: String? = null

    /** Key-event relay lane; single thread so DOWN/UP ordering is preserved. */
    private val relayExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "platform-relay").apply { isDaemon = true }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            token = null // tokens are per-connection server-side; re-auth
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            token = null
        }
    }

    /** Call once at server bootstrap; LightOS's SDK service is already running. */
    fun bind(context: Context) {
        context.applicationContext.bindService(
            Intent(LightConstants.ACTION_BIND_SDK_SERVICE).setPackage(PLATFORM),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    /**
     * Key events are relayed off the SDK server's Binder thread: a slow
     * com.lightos handler must not stall every tool RPC. Events stay ordered
     * (single thread). Fire-and-forget — the caller never inspects the result.
     */
    fun sendDeviceKeyEvent(request: LightServiceMethod.DeviceKeyEvent.Request) {
        relayExecutor.execute {
            val start = SystemClock.elapsedRealtime()
            val result = request(
                LightServiceMethod.DeviceKeyEvent.id,
                LightServiceMethod.DeviceKeyEvent.encodeRequest(request),
            )
            val ms = SystemClock.elapsedRealtime() - start
            if (result == null) {
                Log.e(TAG, "DeviceKeyEvent not relayed (code=${request.keyCode} action=${request.action}) in ${ms}ms")
            } else {
                Log.d(TAG, "DeviceKeyEvent relayed (code=${request.keyCode} action=${request.action}) in ${ms}ms")
            }
        }
    }

    /**
     * Open the dialer prefilled with [phoneNumber] (contact panel CALL,
     * 2026-09-01). Explicit ACTION_DIAL to the AOSP dialer (the toolbox
     * "Phone" entry on the LP3, same component on the emulator): an implicit
     * DIAL resolves to com.lightos/.MainActivity, which just resumes home and
     * swallows the intent; LightOS's OpenDialer RPC is also a no-op on current
     * firmware. Fire-and-forget off the binder thread; NEW_TASK because we
     * launch from a service context. The tool is foreground at tap time, so
     * no background-activity-launch block.
     */
    fun openDialer(context: Context, phoneNumber: String) {
        relayExecutor.execute {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                        .setComponent(
                            ComponentName(
                                "com.android.dialer",
                                "com.android.dialer.main.impl.MainActivity",
                            ),
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                Log.d(TAG, "OpenDialer: launching dialer for $phoneNumber")
            } catch (e: Exception) {
                Log.e(TAG, "OpenDialer failed for $phoneNumber", e)
            }
        }
    }

    /** Real device haptics; null when the platform server is unreachable (fall back to local default). */
    fun getUserPreferences(): LightServiceMethod.GetUserPreferences.Response? {
        val result = request(
            LightServiceMethod.GetUserPreferences.id,
            LightServiceMethod.GetUserPreferences.encodeRequest(Unit),
        ) as? LightResult.Success ?: return null
        return LightServiceMethod.GetUserPreferences.decodeResponse(result.data)
    }

    private fun request(methodId: String, payload: String): LightResult<String>? {
        val serviceBinder = binder ?: return null
        if (token == null) {
            val tokenResult = transact(
                serviceBinder,
                LightServiceMethod.GetToken.id,
                LightServiceMethod.GetToken.encodeRequest(Unit),
                "no_auth", // GetToken is not token-gated; only verified-caller-gated
            ) ?: return null
            val ok = tokenResult as? LightResult.Success ?: return tokenResult
            token = LightServiceMethod.GetToken.decodeResponse(ok.data).token
        }
        return transact(serviceBinder, methodId, payload, token)
    }

    private fun transact(
        serviceBinder: IBinder,
        methodId: String,
        payload: String,
        token: String?,
    ): LightResult<String>? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(LightConstants.ACTION_BIND_SDK_SERVICE)
            data.writeString(methodId)
            data.writeString(payload)
            data.writeString(token)
            serviceBinder.transact(LightConstants.TRANSACTION_REQUEST, data, reply, 0)
            reply.readException()
            val errorOrdinal = reply.readInt()
            if (errorOrdinal == -1) {
                LightResult.Success(reply.readString() ?: "")
            } else {
                val extra = reply.readString()
                val code = LightResult.ErrorCode.entries.getOrElse(errorOrdinal) { LightResult.ErrorCode.Unknown }
                LightResult.Error(code, extra)
            }
        } catch (e: Exception) {
            Log.e(TAG, "transact failed: $methodId", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
