package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import com.unique.core.common.path.VirtualPathModel
import org.junit.Test

/**
 * The paths a guest is told it has, which is the whole of Standoff 2's detection.
 *
 * `docs/STANDOFF2.md` counts every JNI name the game's anti-cheat references: `sourceDir`
 * four times, `getApplicationInfo` three, `getPackageCodePath` twice, `nativeLibraryDir`
 * once, and nothing from `/proc` at all. It puts the first of them into an
 * `AppVerification` protobuf as `Path` and sends it as a field of `GoogleAuthRequest`, and
 * the server answers `AuthRestrictions/VirtualSpaceMessage`.
 *
 * So the property under test is not "the paths look plausible". It is that **no value a
 * guest can read contains `com.unique`**, because an installed app cannot produce one and
 * no comparison is needed to notice it. That is asserted directly below rather than left
 * to be inferred from the shape of the strings.
 *
 * The reflection half of `GuestIdentityPaths` — writing these into `ApplicationInfo` and
 * `LoadedApk`, and the round-trip probe that gates the data directory — needs a real
 * framework and is measured on the device instead, by the probe itself and by the
 * `GUEST_PATHS_PUBLISHED` event it emits.
 */
class GuestIdentityPathsTest {

    private val model = VirtualPathModel("/data/user/0/com.unique/files/virtual")
    private val pkg = "com.axlebolt.standoff2"
    private val versionCode = 203908L

    private fun plan(abi: String = "arm64-v8a", vuid: Int = 1) =
        GuestIdentityPaths.plan(model, pkg, vuid, versionCode, abi)

    @Test fun `nothing a guest can read names UNIQUE`() {
        val p = plan()
        for (path in listOf(p.publicApkDir, p.publicDataDir, p.baseApk, p.nativeLibraryDir)) {
            assertThat(path).doesNotContain("com.unique")
            assertThat(path).doesNotContain("virtual")
        }
    }

    @Test fun `the APK directory has the shape the platform installer produces`() {
        // /data/app/~~<22 base64url>/<pkg>-<22 base64url>
        val dir = plan().publicApkDir
        val match = Regex("^/data/app/~~([A-Za-z0-9_-]{22})/\\Q$pkg\\E-([A-Za-z0-9_-]{22})$")
            .matchEntire(dir)
        assertThat(match).isNotNull()
        // The two tokens are independent; an installer never uses one twice.
        assertThat(match!!.groupValues[1]).isNotEqualTo(match.groupValues[2])
    }

    @Test fun `the library directory is spelled the way an installer extracts it`() {
        // `lib/arm64`, not `lib/arm64-v8a`. The ABI directory inside an APK and the one on
        // disk after installation are different words, and an app checking its own
        // `nativeLibraryDir` against the first spelling has found something.
        assertThat(plan("arm64-v8a").nativeLibraryDir).endsWith("/lib/arm64")
        assertThat(plan("armeabi-v7a").nativeLibraryDir).endsWith("/lib/arm")
        assertThat(plan("x86_64").nativeLibraryDir).endsWith("/lib/x86_64")
    }

    @Test fun `the library directory sits under the APK directory`() {
        val p = plan()
        assertThat(p.nativeLibraryDir).startsWith("${p.publicApkDir}/")
        assertThat(p.baseApk).isEqualTo("${p.publicApkDir}/base.apk")
    }

    @Test fun `the data directory is the one a single-user device would give`() {
        assertThat(plan().publicDataDir).isEqualTo("/data/user/0/$pkg")
        // A second instance of the same app is a different *install*, not a different
        // Android user: reporting `/data/user/1/...` would be a tell of its own, because
        // no ordinary phone runs a game in a secondary user profile.
        assertThat(plan(vuid = 2).publicDataDir).isEqualTo("/data/user/0/$pkg")
    }

    @Test fun `two instances of one app are told they are two different installs`() {
        // They have separate data on disk, so they must not report one directory between
        // them: an app that wrote its config under the first instance's identity and read
        // it back under the second's would see someone else's.
        assertThat(plan(vuid = 1).publicApkDir).isNotEqualTo(plan(vuid = 2).publicApkDir)
    }

    @Test fun `the same instance is told the same install every time it starts`() {
        // Some apps record where they were installed and compare on the next launch. A
        // token that moved between launches would be a change an installed app cannot make
        // without being reinstalled.
        assertThat(plan()).isEqualTo(plan())
    }

    @Test fun `the redirect table maps the published paths back to real ones`() {
        // The half that makes this more than cosmetic. A path handed to a guest that does
        // not open is worse than the real one: it fails as an app that silently cannot
        // read its own files.
        val p = plan()
        val rules = model.redirectionRules(1, pkg, versionCode)
        fun resolve(path: String): String? = rules
            .firstOrNull { path == it.from || path.startsWith(it.from + "/") }
            ?.let { it.to + path.removePrefix(it.from) }

        assertThat(resolve(p.baseApk))
            .isEqualTo(model.apkDir(pkg, versionCode) + "/base.apk")
        assertThat(resolve("${p.nativeLibraryDir}/libunity.so"))
            .isEqualTo(model.nativeLibraryDir(pkg, versionCode, "arm64-v8a") + "/libunity.so")
        assertThat(resolve("${p.publicDataDir}/files/save.dat"))
            .isEqualTo(model.dataDir(1, pkg) + "/files/save.dat")
    }

    @Test fun `splits keep their names and lose their directory`() {
        val rebased = GuestIdentityPaths.rebaseSplits(
            arrayOf(
                "/data/user/0/com.unique/files/virtual/apk/$pkg/203908/split_config.arm64_v8a.apk",
                "/data/user/0/com.unique/files/virtual/apk/$pkg/203908/split_config.ru.apk",
            ),
            "/data/app/~~aaaa/x-bbbb",
        )
        assertThat(rebased!!.toList()).containsExactly(
            "/data/app/~~aaaa/x-bbbb/split_config.arm64_v8a.apk",
            "/data/app/~~aaaa/x-bbbb/split_config.ru.apk",
        ).inOrder()
    }

    @Test fun `an app with no splits is not given one`() {
        // `splitSourceDirs` is null for a single-APK install, and an empty array is not
        // the same thing: `ApplicationInfo.splitSourceDirs != null` is how some code asks
        // whether the app is split at all.
        assertThat(GuestIdentityPaths.rebaseSplits(null, "/data/app/~~a/x-b")).isNull()
    }
}
