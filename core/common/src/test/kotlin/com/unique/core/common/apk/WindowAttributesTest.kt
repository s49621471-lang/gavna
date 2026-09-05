package com.unique.core.common.apk

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The window and task attributes, against real AAPT2 output.
 *
 * These went unread for the whole of phases 1-8, and the cost was not subtle. With
 * `hardwareAccelerated` never parsed, `ActivityInfo.flags` was zero for every virtual
 * activity, and `Activity.attach` passes exactly that bit to `Window.setWindowManager` —
 * so every guest drew in software. On a real phone that is a lag complaint from the user
 * and, for anything that renders through a `RenderNode`, an outright crash:
 *
 * ```
 * CRASH u1 clear.una UNCAUGHT_EXCEPTION thread=main
 *   reason=IllegalArgumentException: Software rendering doesn't support drawRenderNode
 * ```
 *
 * `screenOrientation` is the same shape of omission with a different symptom: it was
 * parsed but never applied, and since the platform uses the *stub's* value a landscape
 * game opened in portrait.
 */
class WindowAttributesTest {

    private fun fixture(name: String): File {
        val url = checkNotNull(javaClass.classLoader.getResource("fixtures/$name")) {
            "missing fixture $name"
        }
        return File(url.toURI())
    }

    private val manifest: ApkManifest by lazy {
        ManifestReader.fromApk(fixture("sample-window.apk"))
    }

    private fun activity(simpleName: String): ComponentEntry =
        manifest.components.single { it.className == "com.example.window.$simpleName" }

    @Test fun `application level attributes are read`() {
        assertThat(manifest.hardwareAccelerated).isTrue()
        assertThat(manifest.largeHeap).isTrue()
        assertThat(manifest.supportsRtl).isTrue()
    }

    @Test fun `an activity inherits the application's hardware acceleration`() {
        assertThat(activity("GameActivity").window.hardwareAccelerated).isTrue()
        assertThat(activity("PlainActivity").window.hardwareAccelerated).isTrue()
    }

    @Test fun `an activity can turn hardware acceleration off for itself`() {
        assertThat(activity("SoftwareActivity").window.hardwareAccelerated).isFalse()
    }

    /**
     * The platform's default when `<application>` says nothing: on for `targetSdk >= 14`.
     *
     * Checked against a manifest that does not mention the attribute at all, because that
     * is what almost every real app looks like — and getting the *default* wrong would
     * have left the original bug in place for all of them.
     */
    @Test fun `an application that says nothing is accelerated on a modern target`() {
        val base = ManifestReader.fromApk(fixture("sample-base.apk"))
        assertThat(base.hardwareAccelerated).isTrue()
        assertThat(base.activities.map { it.window.hardwareAccelerated }).doesNotContain(false)
    }

    @Test fun `screen orientation and config changes survive the decode`() {
        val game = activity("GameActivity")
        // SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        assertThat(game.screenOrientation).isEqualTo(6)
        // orientation | screenSize | keyboardHidden
        assertThat(game.configChanges and 0x0080).isEqualTo(0x0080)  // CONFIG_ORIENTATION
        assertThat(game.configChanges and 0x0400).isEqualTo(0x0400)  // CONFIG_SCREEN_SIZE
        assertThat(activity("SoftwareActivity").screenOrientation).isEqualTo(1) // PORTRAIT
        assertThat(activity("PlainActivity").screenOrientation).isEqualTo(-1)   // UNSPECIFIED
    }

    @Test fun `task and window flags are read`() {
        val game = activity("GameActivity").window
        assertThat(game.excludeFromRecents).isTrue()
        assertThat(game.noHistory).isTrue()
        assertThat(game.resizeable).isFalse()
        assertThat(game.maxAspectRatio).isWithin(0.001f).of(2.4f)
        // adjustPan
        assertThat(game.softInputMode and 0x00f0).isEqualTo(0x0020)

        val plain = activity("PlainActivity").window
        assertThat(plain.excludeFromRecents).isFalse()
        assertThat(plain.noHistory).isFalse()
        assertThat(plain.maxAspectRatio).isEqualTo(0f)
    }

    /**
     * Meta-data keeps its compiled type, which is the half that matters.
     *
     * A textual value cannot be turned back into the `int` Play services reads out of
     * `ApplicationInfo.metaData`; the type and the raw datum can.
     */
    @Test fun `meta-data survives with its type`() {
        val entries = manifest.applicationMetaDataEntries.associateBy { it.name }
        assertThat(entries.keys)
            .containsAtLeast("com.example.flag", "com.example.count", "com.example.layout")

        val flag = entries.getValue("com.example.flag")
        assertThat(flag.valueType).isEqualTo(BinaryXml.TYPE_INT_BOOLEAN)
        assertThat(flag.valueData).isNotEqualTo(0)

        val count = entries.getValue("com.example.count")
        assertThat(count.valueType).isEqualTo(BinaryXml.TYPE_INT_DEC)
        assertThat(count.valueData).isEqualTo(7)

        // `android:resource` is stored as the id and resolved by the app, exactly as the
        // platform does it.
        assertThat(entries.getValue("com.example.layout").resourceId).isNotEqualTo(0)
    }

    @Test fun `a provider's own permissions and grant flag are read`() {
        val provider = manifest.providers.single()
        assertThat(provider.authorities).containsExactly("com.example.window.quiet")
        assertThat(provider.readPermission).isEqualTo("com.example.window.READ")
        assertThat(provider.writePermission).isNull()
        // Never declared, so never granted. It used to be hard-coded true wherever a
        // ProviderInfo was built, which widened every guest provider.
        assertThat(provider.grantUriPermissions).isFalse()
    }

    @Test fun `the base fixture's provider keeps the grant flag it declares`() {
        val base = ManifestReader.fromApk(fixture("sample-base.apk"))
        assertThat(base.providers.single().grantUriPermissions).isTrue()
    }
}
