plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

// ---------------------------------------------------------------------------------
// Build-wide constants. Single source, so no module can drift.
//
// Exposed through `extra` rather than a buildSrc object: buildSrc would drag the Gradle
// Kotlin DSL plugin into every configuration, roughly doubling cold configuration time
// for eleven modules, in exchange for type safety on nine constants.
// ---------------------------------------------------------------------------------
extra["compileSdk"] = 36
extra["targetSdk"] = 36

// API 31. Android 12 is the oldest release UNIQUE targets: below it the hook surface
// (ClientTransaction shape, hidden-API policy, ServiceManager caching) diverges enough
// that supporting it would mean maintaining a second engine.
extra["minSdk"] = 31

// ARM64 only, per ARCHITECTURE.md section 2.
extra["abis"] = setOf("arm64-v8a")

extra["uniqueVersionCode"] = 1
extra["uniqueVersionName"] = "0.1.0-phase1"
extra["applicationId"] = "com.unique"

// Virtual app process slots declared in the host manifest. Each costs a few manifest
// entries and nothing at runtime until a slot is used.
extra["vappProcessCount"] = 16

// Stub activities per process slot: one per launchMode x taskAffinity variant.
extra["stubActivitiesPerProcess"] = 8

/**
 * Shared Android configuration, applied reactively.
 *
 * The 16 KB page-size link flags live in CMakeLists rather than here, but the ABI filter
 * and SDK levels are set once for every module so a new module cannot accidentally ship
 * an ABI UNIQUE does not support.
 */
subprojects {
    plugins.withId("com.android.base") {
        extensions.configure<com.android.build.api.dsl.CommonExtension<*, *, *, *, *, *>>("android") {
            compileSdk = rootProject.extra["compileSdk"] as Int
            defaultConfig {
                minSdk = rootProject.extra["minSdk"] as Int
                @Suppress("UNCHECKED_CAST")
                ndk { abiFilters += rootProject.extra["abis"] as Set<String> }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            lint {
                abortOnError = false
            }
        }
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension>("kotlin") {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                freeCompilerArgs.addAll("-Xjvm-default=all")
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
