plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

tasks.register("assembleDebug") {
    dependsOn(":app:assembleDebug")
}

tasks.register("assembleRelease") {
    dependsOn(":app:assembleRelease")
}

