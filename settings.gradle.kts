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
