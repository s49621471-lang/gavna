package com.unique.core.vstorage

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The instance file browser, and above all what it refuses.
 *
 * This is the one class in the engine that takes a filesystem path from the interface and
 * turns it into a real file. Most of what follows is about the boundary: a path that
 * leaves the instance must come back null, whether it leaves by `..`, by naming another
 * package, or by a symlink a guest planted in its own directory.
 */
class FileBrowserTest {

    @get:Rule val temp = TemporaryFolder()

    private val pkg = "com.example.game"

    private fun browser() = FileBrowser(temp.root.absolutePath)

    private fun realPath(guestPath: String, vuid: Int = 0): String =
        browser().resolve(vuid, pkg, guestPath)!!.path

    @Test fun `an instance has the two trees a guest can see, under the guest's names`() {
        val roots = browser().roots(0, pkg)
        assertThat(roots.map { it.guestPath })
            .containsExactly("/data/data/$pkg", "/sdcard").inOrder()
        // The real path is never the guest path: showing the user where this really is
        // would be showing them UNIQUE's private storage, which is not a fact about
        // their app.
        assertThat(roots.map { it.realPath }).doesNotContain("/sdcard")
    }

    @Test fun `the obb directory a game names is reachable by the name the game uses`() {
        // The whole reason the screen exists: a user is told "put it in
        // Android/obb/<package>" and must be able to get there.
        val resolved = browser().resolve(0, pkg, "/sdcard/Android/obb/$pkg")
        assertThat(resolved).isNotNull()
        assertThat(resolved!!.path).endsWith("/sdcard/Android/obb/$pkg")
    }

    @Test fun `a path that climbs out of the instance is refused`() {
        for (escape in listOf(
            "/sdcard/../../..",
            "/sdcard/Android/../../../../etc/passwd",
            "/data/data/$pkg/../../../..",
        )) {
            assertThat(browser().resolve(0, pkg, escape)).isNull()
        }
    }

    @Test fun `a path belonging to no root is refused, including one that only looks like it`() {
        for (outside in listOf("/etc/passwd", "", "/sdcardX/file", "/data/data/other.app/x")) {
            assertThat(browser().resolve(0, pkg, outside)).isNull()
        }
    }

    @Test fun `a symlink out of the instance is refused, not followed`() {
        val external = temp.newFolder("outside")
        File(external, "secret").writeText("no")
        val sdcard = File(realPath("/sdcard"))
        sdcard.mkdirs()
        try {
            java.nio.file.Files.createSymbolicLink(
                File(sdcard, "escape").toPath(), external.toPath(),
            )
        } catch (unsupported: Exception) {
            return // A filesystem without symlinks cannot fail this way.
        }
        // canonicalPath resolves the link, so the check sees where it really goes.
        assertThat(browser().resolve(0, pkg, "/sdcard/escape/secret")).isNull()
    }

    @Test fun `two instances of one package never see each other's files`() {
        assertThat(realPath("/sdcard/save.dat", vuid = 0))
            .isNotEqualTo(realPath("/sdcard/save.dat", vuid = 1))
    }

    @Test fun `listing shows directories first and is not case-sensitive about the rest`() {
        val root = File(realPath("/sdcard"))
        root.mkdirs()
        File(root, "zebra.txt").writeText("z")
        File(root, "Apple.txt").writeText("a")
        File(root, "Music").mkdirs()
        assertThat(browser().list(0, pkg, "/sdcard").map { it.name })
            .containsExactly("Music", "Apple.txt", "zebra.txt").inOrder()
    }

    @Test fun `listing an instance that has never run creates its roots rather than failing`() {
        // A user can open Files before the first launch, and "this directory does not
        // exist" would be describing an implementation detail, not their app.
        assertThat(browser().list(0, pkg, "/sdcard")).isEmpty()
        assertThat(File(realPath("/sdcard")).isDirectory).isTrue()
    }

    @Test fun `an import lands in the directory the user was looking at`() {
        val result = browser().importInto(
            0, pkg, "/sdcard/Android/obb/$pkg", "main.1.obb",
            "expansion".byteInputStream(),
        )
        assertThat(result.getOrNull()).isEqualTo(9L)
        assertThat(File(realPath("/sdcard/Android/obb/$pkg/main.1.obb")).readText())
            .isEqualTo("expansion")
    }

    @Test fun `an import leaves no half-file behind when it fails`() {
        val failing = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("device is full")
        }
        val result = browser().importInto(0, pkg, "/sdcard", "big.obb", failing)
        assertThat(result.isFailure).isTrue()
        // A `.part` left in place is a file a game would try to load and reject, and the
        // user would have no way to tell it from a finished one.
        assertThat(File(realPath("/sdcard")).list()).isEmpty()
    }

    @Test fun `a name that tries to be a path is taken as a name`() {
        browser().importInto(0, pkg, "/sdcard", "../../escaped.txt", "x".byteInputStream())
        assertThat(File(realPath("/sdcard")).list()?.toList()).containsExactly("escaped.txt")
    }

    @Test fun `deleting a root is refused`() {
        // *Clear data* is the operation that empties an instance, and it asks first.
        // A mis-tap on a row must not be able to do it silently.
        val root = File(realPath("/sdcard"))
        root.mkdirs()
        File(root, "keep.txt").writeText("k")
        assertThat(browser().delete(0, pkg, "/sdcard")).isFalse()
        assertThat(File(root, "keep.txt").exists()).isTrue()
    }

    @Test fun `deleting a directory takes what is under it`() {
        browser().importInto(0, pkg, "/sdcard/logs", "a.txt", "a".byteInputStream())
        assertThat(browser().delete(0, pkg, "/sdcard/logs")).isTrue()
        assertThat(File(realPath("/sdcard/logs")).exists()).isFalse()
    }

    @Test fun `a folder name that is a dot is refused`() {
        for (bad in listOf("..", ".", "")) {
            assertThat(browser().createFolder(0, pkg, "/sdcard", bad)).isFalse()
        }
        assertThat(browser().createFolder(0, pkg, "/sdcard", "obb")).isTrue()
    }

    @Test fun `a folder name that is a path is taken as a name, like an import`() {
        // Sanitised rather than refused, and the same way `importInto` does it: the
        // interesting property is that nothing lands outside the instance, and both
        // reaching that by keeping the last segment is one rule rather than two.
        assertThat(browser().createFolder(0, pkg, "/sdcard", "../../etc")).isTrue()
        assertThat(File(realPath("/sdcard")).list()?.toList()).containsExactly("etc")
    }
}

/**
 * The rule that told every app its game files were blocked.
 *
 * `GuestAssetImport` used to report `SOURCE_UNREADABLE … grant all-files access` whenever
 * a package's `Android/obb` directory could not be seen. On Android 11 and later that is
 * every package — `MANAGE_EXTERNAL_STORAGE` does not cover `Android/data` or
 * `Android/obb`, so the directory is invisible whether it has files or not. One device
 * log carried the message for a chat app, a cleaner and a file manager, none of which has
 * ever shipped an expansion file, and the user granted the access it asked for and
 * nothing changed. It could not have.
 */
class InvisibleSourceRuleTest {

    @Test fun `an invisible source is not reported as something the user can fix`() {
        // The temptation to infer "blocked" from "cannot see it" is what produced the
        // fault, and inferring it is wrong on every release that hides the directory.
        // SOURCE_UNREADABLE is what the UI turns into an instruction, so this assertion
        // is the difference between a message that helps and one that appears on every
        // app and cannot be acted on.
        assertThat(GuestAssetImport.invisibleSourceOutcome())
            .isEqualTo(GuestAssetImport.Outcome.NOTHING_TO_IMPORT)
    }
}
