plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.unique.core.vam"
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    api(project(":core:common"))
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":core:hook"))
    implementation(project(":core:vpm"))
    implementation(project(":core:vstorage"))
    implementation(project(":core:native"))
    implementation(project(":core:vprofile"))
    implementation(project(":core:vprocess"))
    implementation(project(":core:vpermission"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:google"))
}
