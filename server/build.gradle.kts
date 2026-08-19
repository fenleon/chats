plugins {
    // Version-less by design: the plugin is already on the build classpath
    // (the composite's included light-sdk root declares it `apply false`, and
    // :app loads AGP). A versioned request here is rejected with "already on
    // the classpath with an unknown version" (Gradle composite quirk); the
    // classpath version IS the catalog's agp (8.12.3), so nothing is lost.
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.lightphone.chats.server"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        // R8 runs at the app level (the merged tool APK minifies); these keep
        // rules (JNA / Trixnity olm) must reach that run via the consumer.
        consumerProguardFiles("proguard-rules.pro")
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
    // The QR scanner + CameraX come transitively via sdk:ui; the chat server never
    // scans codes (the merged manifest even strips CAMERA).
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
