plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.unique.core.vstorage"
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    api(project(":core:common"))
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":core:hook"))
    implementation(project(":core:native"))
    implementation(project(":core:diagnostics"))
}
