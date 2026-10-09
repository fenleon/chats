plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// SDK build properties, matching the light-sdk repo's root build.gradle.kts —
// tool/build.gradle.kts reads these so it also builds unchanged when staged
// into Light's Tool Library builder (which supplies its own root).
ext {
    set("compileSdk", 36)
    set("minSdk", 34)
    set("targetSdk", 36)
    set("jvmTarget", "17")
}
