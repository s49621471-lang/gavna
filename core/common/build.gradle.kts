plugins {
    alias(libs.plugins.kotlin.jvm)
}

// core:common is deliberately a PURE JVM module.
// It must never depend on android.* so that the correctness-critical models
// (path contract, APK parsing, ELF checks, device profiles) stay unit-testable
// off-device. An accidental Android dependency is caught by :core:common:check.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "skipped", "failed") }
}
