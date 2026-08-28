# JNA — Trixnity's olm crypto wrapper loads libjnidispatch through JNA, which is
# reflection/JNI-heavy (field IDs looked up by name). R8 must not obfuscate or
# strip it, or E2EE login fails with:
#   UnsatisfiedLinkError: Can't obtain peer field ID for class com.sun.jna.Pointer
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Trixnity loads its olm crypto backend reflectively (optional engine) — without a
# keep, R8 strips it and session restore dies with:
#   NoClassDefFoundError: ...OlmLibraryWrapper (obfuscated as J5.d)
-keep class de.connect2x.trixnity.libolm.** { *; }

# Optional AndroidX window extensions (may be absent on some devices; unused here).
-dontwarn androidx.window.extensions.area.ExtensionWindowAreaPresentation
-dontwarn androidx.window.extensions.core.util.function.Consumer
-dontwarn androidx.window.extensions.core.util.function.Function
-dontwarn androidx.window.extensions.core.util.function.Predicate
