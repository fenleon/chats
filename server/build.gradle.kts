plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.lightphone.chats.server"
    compileSdk = 36

    signingConfigs {
        // Workspace dev signing (same key as the SDK tools/emulator).
        create("lightsdkDev") {
            storeFile = file("../../light-sdk/sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.lightphone.chats.server"
        minSdk = 34
        targetSdk = 36
        // Public release. versionCode tracks the repo's commit count
        // (25 commits at 0.2.0).
        versionCode = 25
        versionName = "0.2.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true      // R8: dead-code elimination + obfuscation
            isShrinkResources = true    // drop unused resources
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // SDK modules come from the included ../light-sdk build (see settings.gradle.kts).
    // The QR scanner + CameraX come transitively via sdk:ui; the companion is a chat
    // server and never scans codes (its manifest even strips CAMERA).
    implementation(libs.sdk.server)   // LightSdkServer + LightSdkService (the binder)
    implementation(libs.sdk.client) { // binder client + service connection
        exclude(group = "com.google.mlkit")
        exclude(group = "androidx.camera")
    }
    implementation(libs.sdk.ui) {     // Light design system for the status screen
        exclude(group = "com.google.mlkit")
        exclude(group = "androidx.camera")
    }
    implementation(libs.compose.activity)
    implementation(libs.kotlinx.coroutines)

    // Trixnity Matrix SDK (the protocol layer: login/sync, room repositories, media).
    // Version pair proven by the Beeper4LightOS bootstrap on LightOS (see chats/PLAN.md).
    implementation(libs.trixnity.client)
    implementation(libs.trixnity.repository.room)
    implementation(libs.trixnity.media.okio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    // Room runtime for Trixnity's TrixnityRoomDatabase (session + event store).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
}
