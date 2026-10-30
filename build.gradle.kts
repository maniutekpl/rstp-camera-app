// Root build file: declares plugin versions, applied in the app module.
plugins {
    id("com.android.application") version "9.5.0" apply false
    // Not applied anywhere (AGP 9 has Kotlin built in); only pins the Kotlin version
    // so it matches the Compose compiler plugin below.
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
