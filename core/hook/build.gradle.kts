plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.unique.core.hook"
}

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.hiddenapibypass)
    implementation(project(":core:diagnostics"))
    // LSPlant is deliberately NOT a dependency yet. It is a native (prefab) ART method
    // hook and belongs in :core:native when a call site is genuinely unreachable through
    // the Binder shim layer - see ARCHITECTURE.md section 4.3. Pulling it in early would
    // add a native dependency to a pure-Kotlin module for no current benefit.
}
