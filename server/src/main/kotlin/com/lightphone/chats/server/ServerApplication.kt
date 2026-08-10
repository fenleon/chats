package com.lightphone.chats.server

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.thelightphone.sdk.server.ClientCertType
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.DefaultLightSdkServerSettings
import com.thelightphone.sdk.server.LightSdkServer
import java.security.MessageDigest

// SHA-256 fingerprint of sdk/keys/lightsdk-dev.jks (alias: lightsdk-dev). Any
// APK signed with the workspace dev key is treated as Light-SDK-signed.
private const val LIGHTSDK_DEV_CERT_SHA256 =
    "B9C33E29B0CCAD2BFF11ACAB55F65A3C517EF4BC92CD9C77785366FA353D5F28"

/**
 * The Chats companion: hosts the SDK's [LightSdkService] so the tool can bind
 * to it. Phase 2 adds the persistent Matrix connection (sync loop), storage,
 * and notifications.
 */
class ServerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        with(LightSdkServer) {
            defaultClientFilterLevel = ClientFilterLevel.AllowLightSignedApks
            provideSdkSettings = { DefaultLightSdkServerSettings(it) }
            checkCert = { callingPackage -> checkLightSdkCert(callingPackage) }
            customServiceMethodResolver = { callingId, methodId, payload ->
                ChatServiceMethods.dispatch(methodId, payload)
            }
        }
    }

    private fun Context.checkLightSdkCert(callingPackage: String): ClientCertType {
        val info = try {
            packageManager.getPackageInfo(callingPackage, PackageManager.GET_SIGNING_CERTIFICATES)
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
}
