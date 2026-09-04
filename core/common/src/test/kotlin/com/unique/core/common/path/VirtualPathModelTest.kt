package com.unique.core.common.path

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The path contract is the single largest source of silent data corruption in app
 * virtualization, so it is pinned here rather than discovered on a device.
 */
class VirtualPathModelTest {

    private val model = VirtualPathModel("/data/user/0/com.unique/files")
    private val pkg = "com.example.sample"
    private val vc = 4210L

    @Test fun `apk storage is shared per package version, not per instance`() {
        assertThat(model.apkDir(pkg, vc))
            .isEqualTo("/data/user/0/com.unique/files/virtual/apk/com.example.sample/4210")
        assertThat(model.baseApk(pkg, vc)).endsWith("/4210/base.apk")
        assertThat(model.splitApk(pkg, vc, "config.arm64_v8a"))
            .endsWith("/4210/split_config.arm64_v8a.apk")
        assertThat(model.nativeLibraryDir(pkg, vc)).endsWith("/4210/lib/arm64-v8a")
    }

    @Test fun `every instance directory lives under its own user root`() {
        for (vuid in 0..3) {
            for (dir in model.instanceDirectories(vuid, pkg)) {
                assertThat(model.belongsToInstance(dir, vuid)).isTrue()
            }
        }
    }

    @Test fun `two instances never share a writable directory`() {
        val a = model.instanceDirectories(0, pkg).toSet()
        val b = model.instanceDirectories(1, pkg).toSet()
        assertThat(a.intersect(b)).isEmpty()
    }

    @Test fun `the documented accessor table holds`() {
        val data = model.dataDir(0, pkg)
        assertThat(model.filesDir(0, pkg)).isEqualTo("$data/files")
        assertThat(model.cacheDir(0, pkg)).isEqualTo("$data/cache")
        assertThat(model.codeCacheDir(0, pkg)).isEqualTo("$data/code_cache")
        assertThat(model.noBackupDir(0, pkg)).isEqualTo("$data/no_backup")
        assertThat(model.databasesDir(0, pkg)).isEqualTo("$data/databases")
        assertThat(model.sharedPrefsDir(0, pkg)).isEqualTo("$data/shared_prefs")
        assertThat(model.externalFilesDir(0, pkg))
            .isEqualTo("${model.externalRoot(0)}/Android/data/$pkg/files")
        assertThat(model.obbDir(0, pkg)).isEqualTo("${model.externalRoot(0)}/Android/obb/$pkg")
    }

    @Test fun `redirection rules are ordered longest prefix first`() {
        val rules = model.redirectionRules(0, pkg, vc)
        val lengths = rules.map { it.from.length }
        assertThat(lengths).isEqualTo(lengths.sortedDescending())
    }

    @Test fun `hard-coded data paths land in the right instance`() {
        val t0 = RedirectTable(model.redirectionRules(0, pkg, vc))
        val t1 = RedirectTable(model.redirectionRules(1, pkg, vc))

        val hardCoded = "/data/data/$pkg/databases/messages.db"
        assertThat(t0.redirect(hardCoded)).isEqualTo("${model.dataDir(0, pkg)}/databases/messages.db")
        assertThat(t1.redirect(hardCoded)).isEqualTo("${model.dataDir(1, pkg)}/databases/messages.db")
        assertThat(t0.redirect(hardCoded)).isNotEqualTo(t1.redirect(hardCoded))
    }

    @Test fun `every spelling of the data directory is covered`() {
        val t = RedirectTable(model.redirectionRules(0, pkg, vc))
        val expected = "${model.dataDir(0, pkg)}/files/x"
        assertThat(t.redirect("/data/data/$pkg/files/x")).isEqualTo(expected)
        assertThat(t.redirect("/data/user/0/$pkg/files/x")).isEqualTo(expected)
        assertThat(t.redirect("/data/user_de/0/$pkg/files/x")).isEqualTo(expected)
    }

    @Test fun `every spelling of external storage is covered`() {
        val t = RedirectTable(model.redirectionRules(0, pkg, vc))
        val expected = "${model.externalRoot(0)}/DCIM/a.jpg"
        assertThat(t.redirect("/sdcard/DCIM/a.jpg")).isEqualTo(expected)
        assertThat(t.redirect("/storage/emulated/0/DCIM/a.jpg")).isEqualTo(expected)
        assertThat(t.redirect("/storage/self/primary/DCIM/a.jpg")).isEqualTo(expected)
        assertThat(t.redirect("/mnt/sdcard/DCIM/a.jpg")).isEqualTo(expected)
    }

    @Test fun `unrelated paths pass through untouched`() {
        val t = RedirectTable(model.redirectionRules(0, pkg, vc))
        assertThat(t.redirect("/system/lib64/libc.so")).isEqualTo("/system/lib64/libc.so")
        assertThat(t.redirect("/proc/self/maps")).isEqualTo("/proc/self/maps")
        // Another package's data must not be captured by this instance's rules.
        assertThat(t.redirect("/data/data/com.other.app/files/x"))
            .isEqualTo("/data/data/com.other.app/files/x")
    }

    @Test fun `a rule cannot be defeated by dot segments`() {
        val t = RedirectTable(model.redirectionRules(0, pkg, vc))
        assertThat(t.redirect("/data/data/$pkg/./files/../files/x"))
            .isEqualTo("${model.dataDir(0, pkg)}/files/x")
    }

    @Test fun `normalize collapses traversal`() {
        assertThat(VirtualPathModel.normalize("/a/b/../c/./d//e")).isEqualTo("/a/c/d/e")
        assertThat(VirtualPathModel.normalize("/../..")).isEqualTo("/")
        assertThat(VirtualPathModel.normalize("a/../b")).isEqualTo("b")
    }

    @Test fun `isVirtual distinguishes UNIQUE-owned paths`() {
        assertThat(model.isVirtual(model.dataDir(2, pkg))).isTrue()
        assertThat(model.isVirtual("/data/data/com.unique/files/other")).isFalse()
        assertThat(model.isVirtual("/sdcard")).isFalse()
    }
}
