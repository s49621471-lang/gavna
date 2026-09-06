package com.unique.core.common.path

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a guest's own `/proc` says about it.
 *
 * Redirection covers the paths a guest hands out. This covers the ones it is handed, and
 * there is one file that hands out all of them at once. Inside the `:vapp0` that ran
 * Standoff 2 on the eleventh phone run, `/proc/self/maps` contained both of these:
 *
 * ```
 * … /data/app/~~eOlB8_…/com.unique-LcSgGP…/base.apk
 * … /data/user/0/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908/…/libunity.so
 * ```
 *
 * An installed app's maps names its own package and nothing else. Reading it costs one
 * `fopen` and no permission, and every native crash handler already does it — so an app
 * that wanted to check has the code for an innocent reason.
 *
 * Applying the rules is the native side's job and is tested there
 * (`tools/native-test/proc_view_test.cpp`, against real lines from that log). What is
 * pinned here is that the rules *say the right thing*: they are the inverse of the
 * redirection table, and a rule that is nearly right — `lib/arm64-v8a` where an installed
 * app has `lib/arm64` — is worse than none, because it is a path worth looking at twice.
 */
class ProcViewRulesTest {

    private val model = VirtualPathModel("/data/user/0/com.unique/files")
    private val pkg = "com.axlebolt.standoff2"
    private val vc = 203908L
    private val hostSourceDir =
        "/data/app/~~eOlB8_n2llAc3Bs06uk3yQ==/com.unique-LcSgGPsGIC2VRZTEGkF33g==/base.apk"
    private val hostDataDir = "/data/user/0/com.unique"

    private fun rules(vuid: Int = 0) = model.procViewRules(
        vuid = vuid,
        packageName = pkg,
        versionCode = vc,
        hostSourceDir = hostSourceDir,
        hostDataDir = hostDataDir,
    )

    /** Applies the table the way the native side does: longest prefix first. */
    private fun show(path: String, vuid: Int = 0): String {
        for (rule in rules(vuid)) rule.apply(path)?.let { return it }
        return path
    }

    private fun installedDir(vuid: Int = 0) = model.installedApkDir(
        pkg, VirtualPathModel.installTokenFor(vuid, pkg, vc),
    )

    @Test fun `the guest's own library reads as installed`() {
        assertThat(show("${model.nativeLibraryDir(pkg, vc)}/libunity.so"))
            .isEqualTo("${installedDir()}/lib/arm64/libunity.so")
    }

    @Test fun `an installed app's library directory is arm64, not arm64-v8a`() {
        // The APK carries `lib/arm64-v8a`; the installer extracts to `lib/arm64`. Getting
        // this wrong leaves a path that is nearly right, which is the worst kind.
        assertThat(show("${model.nativeLibraryDir(pkg, vc)}/libfmod.so"))
            .doesNotContain("arm64-v8a")
        assertThat(VirtualPathModel.installedAbiDirName("armeabi-v7a")).isEqualTo("arm")
        assertThat(VirtualPathModel.installedAbiDirName("x86_64")).isEqualTo("x86_64")
    }

    @Test fun `the guest's own APK reads as installed`() {
        assertThat(show(model.baseApk(pkg, vc))).isEqualTo("${installedDir()}/base.apk")
        assertThat(show(model.splitApk(pkg, vc, "config.arm64_v8a")))
            .isEqualTo("${installedDir()}/split_config.arm64_v8a.apk")
    }

    @Test fun `the guest's own data reads as installed`() {
        assertThat(show("${model.filesDir(0, pkg)}/prefs.xml"))
            .isEqualTo("/data/user/0/$pkg/files/prefs.xml")
    }

    @Test fun `the guest's storage reads as the sdcard`() {
        assertThat(show("${model.obbDir(0, pkg)}/main.203908.$pkg.obb"))
            .isEqualTo("/storage/emulated/0/Android/obb/$pkg/main.203908.$pkg.obb")
    }

    @Test fun `UNIQUE's own APK reads as more of the guest's own code`() {
        assertThat(show(hostSourceDir)).isEqualTo("${installedDir()}/base.apk")
        assertThat(show(hostSourceDir.substringBeforeLast('/') + "/lib/arm64/libunique_native.so"))
            .isEqualTo("${installedDir()}/lib/arm64/libunique_native.so")
    }

    /**
     * The rule for UNIQUE's whole private directory is a prefix of every other rule, and
     * must never be the one that wins.
     */
    @Test fun `a specific rule always beats the catch-all for UNIQUE's directory`() {
        assertThat(show("${model.filesDir(0, pkg)}/x")).startsWith("/data/user/0/$pkg/files")
        assertThat(show("${model.nativeLibraryDir(pkg, vc)}/x")).startsWith(installedDir())
        assertThat(rules()).isInStrictOrder(compareByDescending<RedirectRule> { it.from.length })
    }

    @Test fun `nothing of UNIQUE's own is left readable`() {
        val leaked = listOf(
            model.baseApk(pkg, vc),
            model.nativeLibraryDir(pkg, vc) + "/libunity.so",
            model.filesDir(0, pkg) + "/x",
            model.cacheDir(0, pkg) + "/y",
            model.externalRoot(0) + "/Android/data/$pkg/files/z",
            model.obbDir(0, pkg),
            model.runtimeDir() + "/profiles/0.properties",
            hostSourceDir,
            hostDataDir + "/files/anything",
        ).map(::show).filter { it.contains("com.unique") }
        assertThat(leaked).isEmpty()
    }

    @Test fun `the platform's own paths are not touched`() {
        for (path in listOf(
            "/apex/com.android.art/lib64/libart.so",
            "/system/lib64/libandroid_runtime.so",
            "/data/app/~~aaa/com.other.app-bbb/base.apk",
            "/proc/self/maps",
        )) {
            assertThat(show(path)).isEqualTo(path)
        }
    }

    @Test fun `an install token has the shape Android 11 gives one`() {
        val (a, b) = VirtualPathModel.installTokenFor(0, pkg, vc)
        for (token in listOf(a, b)) {
            assertThat(token).hasLength(22)
            assertThat(token.all { it.isLetterOrDigit() || it == '-' || it == '_' }).isTrue()
        }
        assertThat(a).isNotEqualTo(b)
        assertThat(installedDir()).startsWith("/data/app/~~")
        assertThat(installedDir()).contains("/$pkg-")
    }

    @Test fun `the token is stable for an instance and different between instances`() {
        assertThat(VirtualPathModel.installTokenFor(0, pkg, vc))
            .isEqualTo(VirtualPathModel.installTokenFor(0, pkg, vc))
        assertThat(VirtualPathModel.installTokenFor(1, pkg, vc))
            .isNotEqualTo(VirtualPathModel.installTokenFor(0, pkg, vc))
        assertThat(VirtualPathModel.installTokenFor(0, "org.example.other", vc))
            .isNotEqualTo(VirtualPathModel.installTokenFor(0, pkg, vc))
    }

    @Test fun `a second instance shows a second install directory`() {
        assertThat(show("${model.filesDir(1, pkg)}/x", vuid = 1))
            .isEqualTo("/data/user/0/$pkg/files/x")
        assertThat(installedDir(1)).isNotEqualTo(installedDir(0))
    }

    /**
     * The view and the redirector must describe the same layout in opposite directions.
     *
     * A view that answers with a path the redirector does not send back is worse than no
     * view at all: an app that reads its maps, takes a path out of it and opens it gets a
     * file that is not there, which is a stranger fault than the one being fixed.
     */
    @Test fun `every path the view produces is one redirection sends back`() {
        val redirect = RedirectTable(model.redirectionRules(0, pkg, vc))
        for (real in listOf(
            "${model.filesDir(0, pkg)}/prefs.xml",
            "${model.cacheDir(0, pkg)}/a",
            "${model.databasesDir(0, pkg)}/m.db",
            "${model.obbDir(0, pkg)}/main.obb",
            "${model.externalFilesDir(0, pkg)}/f",
            model.baseApk(pkg, vc),
            "${model.nativeLibraryDir(pkg, vc)}/libunity.so",
        )) {
            assertThat(redirect.redirect(show(real))).isEqualTo(real)
        }
    }
}
