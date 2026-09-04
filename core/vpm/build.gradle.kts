plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.unique.core.vpm"

    // Room's exported schemas are checked in so migrations can be written against a real
    // previous schema rather than from memory, and so a schema change that is not
    // accompanied by a migration shows up in review as a diff.
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":core:hook"))
    implementation(project(":core:vstorage"))
    implementation(project(":core:native"))
    implementation(project(":core:diagnostics"))
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
