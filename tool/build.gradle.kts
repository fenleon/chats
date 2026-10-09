plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization) // durable push queue JSON (MatrixRepository QueuedPush)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    // Version-less fallbacks (rootProject.ext): inside this workspace the root
    // build.gradle.kts doesn't declare them — the included ../light-sdk build
    // does via its convention; in Light's Tool Library builder (which stages
    // this tool/ into the baked-in SDK repo) the SDK's root declares them.
    compileSdk = rootProject.ext["compileSdk"] as Int

    packaging {
        // Netty (via the SDK's ktor stack) repeats these per-jar index files;
        // the merger refuses duplicates without an exclude.
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
        )
    }

    signingConfigs {
        // Workspace dev signing (same key as the SDK tools/emulator). Inside
        // an SDK checkout (Light's Tool Library builder stages this tool/
        // module into the baked-in SDK repo) the keys live at ../sdk/keys.
        create("lightsdkDev") {
            storeFile = file(
                listOf("../../light-sdk/sdk/keys/lightsdk-dev.jks", "../sdk/keys/lightsdk-dev.jks")
                    .map(::file).first { it.exists() }
            )
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
        }
    }

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        // Consumed by the plugin's generated manifest (SDK_VERSION metadata).
        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildFeatures {
        // MatrixRepository's verbose-log gate (BuildConfig.DEBUG && pref);
        // the runtime pref stays the real off-switch (the LP3 runs DEBUGGABLE
        // APKs — workspace AGENTS.md).
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        getByName("release") {
            isMinifyEnabled = true      // R8: dead-code elimination + obfuscation
            isShrinkResources = true    // drop unused resources
            signingConfig = signingConfigs.getByName("lightsdkDev")
            // R8 keeps ported from the former :server library's
            // consumerProguardFiles (JNA / Trixnity libolm) — see the file.
            proguardFile("chats-r8.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // v5 Trixnity Room repositories declare their transaction-bound
        // methods with Kotlin context parameters
        // (context(ReadTransaction) / context(WriteTransaction)).
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

// zxing-cpp (the SDK's camera QR/barcode decoder, added for Passes' bit-matrix
// rendering) requires compileSdk 37; chats never scans codes. Per-dependency
// excludes don't prune it through the composite-build project substitution,
// so drop the group for the whole configuration.
configurations.configureEach {
    exclude(group = "com.github.markusfisch")
}

// Inside an SDK checkout the SDK modules are sibling projects; in this
// workspace the included ../light-sdk build substitutes the module artifacts.
val inSdkRepo = file("../sdk").exists()

dependencies {
    // SDK modules come from the included ../light-sdk build (see settings.gradle.kts).
    // The QR scanner + CameraX come transitively via sdk:ui; the chat tool never scans codes.
    if (inSdkRepo) {
        implementation(project(":sdk:client")) {   // LightScreen, LightActivity, SealedLightContext
            exclude(group = "com.google.mlkit")
            exclude(group = "androidx.camera")
        }
    } else {
        implementation("com.thelightphone:sdk-client") {
            exclude(group = "com.google.mlkit")
            exclude(group = "androidx.camera")
        }
    }

    // The Matrix runtime, formerly the :server companion library (Rung-1
    // fold, 2026-10): everything moved in-process behind the tool's
    // SealedLightContext — no LightSdkService, no embedded server.
    implementation(libs.kotlinx.coroutines)

    // Trixnity Matrix SDK (the protocol layer: login/sync, room repositories, media).
    // Version pair proven by the Beeper4LightOS bootstrap on LightOS (see chats/PLAN.md).
    implementation(libs.trixnity.client)
    implementation(libs.trixnity.repository.room)
    implementation(libs.trixnity.media.okio)
    implementation(libs.trixnity.cryptodriver.libolm) // libOlm driver — same pickle format as v4
    implementation(libs.ktor.client.okhttp)
    // Room runtime for Trixnity's TrixnityRoomDatabase (session + event store).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // Unit tests for the pure-logic helpers (kotlin-test only — the
    // workspace's only allowed test framework; junit is its backend).
    testImplementation(libs.kotlin.test)
}
