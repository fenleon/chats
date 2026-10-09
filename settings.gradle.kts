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

include(":tool")

// Chats is a single scanned :tool module (Rung-1 fold, 2026-10): the Matrix
// stack (Trixnity, Room) runs in-process behind the tool's SealedLightContext,
// the former :server companion module is gone, and lighttool.toml points at
// com.lightos. History: it was a tool + companion APK pair until 2026-08-19,
// then a merged single APK with an embedded :server library module. Details
// in chats/PLAN.md.
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
