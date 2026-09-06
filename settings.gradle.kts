pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sdk:ui api-exposes com.github.lightphone:light-keyboard (font);
        // the composite resolves it against the consumer's repositories.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "chats"

include(":app")
include(":server")

// Chats is a single-APK project since 2026-08-19: `:app` is the real LightOS
// tool (lighttool.toml + the light-sdk tool plugin, LightScreen UI); `:server`
// is the merged companion as an Android LIBRARY whose manifest contributes the
// SDK server components (LightSdkService, ChatSyncService, photo/voice
// activities) and whose ServerBootstrapProvider wires the SDK server + Matrix
// sync at app start. The tool binds to itself (lighttool.toml serverPackage =
// com.lightphone.chats). Both consume the SDK as an included build.
includeBuild("../light-sdk") {
    dependencySubstitution {
        substitute(module("com.thelightphone:sdk-ui")).using(project(":sdk:ui"))
        substitute(module("com.thelightphone:sdk-client")).using(project(":sdk:client"))
        substitute(module("com.thelightphone:sdk-server")).using(project(":sdk:server"))
        substitute(module("com.thelightphone:sdk-shared")).using(project(":sdk:shared"))
    }
}

// Local Trixnity patch (OTK regen + /keys/upload off the sync emit path, see
// LIGHT-SDK-PATCHES.md). Conditional: on machines without the ../trixnity clone
// (e.g. CI) the upstream 5.8.0 artifact from Maven Central is used unchanged.
if (file("../trixnity").exists()) {
    includeBuild("../trixnity") {
        dependencySubstitution {
            substitute(module("de.connect2x.trixnity:trixnity-crypto")).using(project(":trixnity-crypto"))
        }
    }
}
