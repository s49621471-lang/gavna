plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.unique.core.nativebridge"

    defaultConfig {
        externalNativeBuild {
            cmake {
                // 16 KB page alignment is mandatory for Android 15 devices with 16 KB
                // pages and for anything targeting API 36. Set here as well as in
                // CMakeLists so it cannot be lost by a toolchain change.
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-fno-rtti", "-fno-exceptions", "-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.annotation)
}
