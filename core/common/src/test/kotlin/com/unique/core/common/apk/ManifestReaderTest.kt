package com.unique.core.common.apk

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Verified against real AAPT2 output (build-tools 36.0.0), not hand-assembled bytes —
 * the point is to prove the decoder handles what current tooling actually emits.
 */
class ManifestReaderTest {

    private fun fixture(name: String): File {
        val url = checkNotNull(javaClass.classLoader.getResource("fixtures/$name")) {
            "missing fixture $name"
        }
        return File(url.toURI())
    }

    private val base: ApkManifest by lazy { ManifestReader.fromApk(fixture("sample-base.apk")) }

    @Test fun `reads package identity`() {
        assertThat(base.packageName).isEqualTo("com.example.sample")
        assertThat(base.versionCode).isEqualTo(4210L)
        assertThat(base.versionName).isEqualTo("3.7.1")
        assertThat(base.minSdk).isEqualTo(26)
        assertThat(base.targetSdk).isEqualTo(35)
        assertThat(base.splitName).isNull()
    }

    @Test fun `reads permissions`() {
        assertThat(base.usesPermissions).containsExactly(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
            "com.google.android.c2dm.permission.RECEIVE",
        )
        assertThat(base.declaredPermissions.map { it.name })
            .containsExactly("com.example.sample.PRIVATE")
    }

    @Test fun `qualifies relative class names against the package`() {
        assertThat(base.applicationClassName).isEqualTo("com.example.sample.SampleApp")
        assertThat(base.activities.map { it.className })
            .containsExactly("com.example.sample.ui.MainActivity", "com.example.sample.ui.AuthActivity")
    }

    @Test fun `qualifies private process names`() {
        val push = base.services.single()
        assertThat(push.className).isEqualTo("com.example.sample.push.PushService")
        assertThat(push.processName).isEqualTo("com.example.sample:push")
        assertThat(base.applicationProcess).isEqualTo("com.example.sample")
    }

    @Test fun `process set drives the virtual process pool`() {
        assertThat(base.processNames)
            .containsExactly("com.example.sample", "com.example.sample:push")
    }

    @Test fun `finds the launcher activity`() {
        assertThat(base.launcherActivity?.className).isEqualTo("com.example.sample.ui.MainActivity")
    }

    @Test fun `reads activity attributes needed for faithful launching`() {
        val main = base.activities.first { it.className.endsWith("MainActivity") }
        assertThat(main.exported).isTrue()
        assertThat(main.launchMode).isEqualTo(2) // singleTask
        assertThat(main.taskAffinity).isEqualTo("com.example.sample.main")
    }

    @Test fun `detects deep-link schemes, the OAuth passthrough surface`() {
        assertThat(base.deepLinkSchemes).containsExactly("com.example.sample")
        val auth = base.activities.first { it.className.endsWith("AuthActivity") }
        assertThat(auth.hasDeepLink).isTrue()
        assertThat(auth.intentFilters.single().hosts).containsExactly("oauth2redirect")
    }

    @Test fun `reads provider authorities as a list`() {
        assertThat(base.providers.single().authorities)
            .containsExactly("com.example.sample.provider", "com.example.sample.files")
    }

    @Test fun `reads receivers and their filters`() {
        val receiver = base.receivers.single()
        assertThat(receiver.className).isEqualTo("com.example.sample.BootReceiver")
        assertThat(receiver.intentFilters.single().actions)
            .containsExactly("android.intent.action.BOOT_COMPLETED")
    }

    @Test fun `reads application meta-data`() {
        assertThat(base.applicationMetaData).containsEntry("com.google.android.gms.version", "12451000")
    }

    @Test fun `detects google integration from manifest evidence`() {
        assertThat(base.usesGoogleSignIn).isTrue()
    }

    @Test fun `a literal label carries no resource id`() {
        assertThat(base.label).isEqualTo("Sample")
        assertThat(base.labelResId).isEqualTo(0)
    }

    @Test fun `a referenced label is reported as both the reference and the id`() {
        // How real applications name themselves. The textual form is what the manifest
        // literally says — `@7f010000` — and it is not a name: a physical-device run
        // listed every imported app as exactly that. The resource id beside it is what
        // the platform needs to resolve it, and what `ApplicationInfo.labelRes` is set
        // from so a guest asking its own PackageManager gets its name rather than its
        // package.
        val labelled = ManifestReader.fromApk(fixture("sample-labelled.apk"))
        assertThat(labelled.packageName).isEqualTo("com.example.labelled")
        assertThat(labelled.label).startsWith("@")
        assertThat(labelled.labelResId).isNotEqualTo(0)
        // The reference and the id are the same number, written two ways. If they ever
        // disagree, one of them is being read from the wrong attribute.
        assertThat(labelled.label).isEqualTo("@" + Integer.toHexString(labelled.labelResId))
    }

    @Test fun `reads a config split manifest`() {
        val split = ManifestReader.fromApk(fixture("sample-split-abi.apk"))
        assertThat(split.packageName).isEqualTo("com.example.sample")
        assertThat(split.splitName).isEqualTo("config.arm64_v8a")
        assertThat(split.hasCode).isFalse()
    }

    @Test fun `reads a feature split manifest`() {
        val split = ManifestReader.fromApk(fixture("sample-split-feature.apk"))
        assertThat(split.splitName).isEqualTo("dynamicfeature")
        assertThat(split.isFeatureSplit).isTrue()
        assertThat(split.activities.map { it.className })
            .containsExactly("com.example.sample.feature.FeatureActivity")
    }

    @Test fun `rejects a non-xml payload instead of returning garbage`() {
        val e = runCatching { ManifestReader.fromBytes(ByteArray(64) { 0x41 }) }.exceptionOrNull()
        assertThat(e).isInstanceOf(BinaryXmlException::class.java)
    }

    @Test fun `class name qualification matches the platform rules`() {
        assertThat(ManifestReader.qualify(".Foo", "a.b")).isEqualTo("a.b.Foo")
        assertThat(ManifestReader.qualify("Foo", "a.b")).isEqualTo("a.b.Foo")
        assertThat(ManifestReader.qualify("c.d.Foo", "a.b")).isEqualTo("c.d.Foo")
        assertThat(ManifestReader.qualifyProcess(":remote", "a.b")).isEqualTo("a.b:remote")
        assertThat(ManifestReader.qualifyProcess("shared.proc", "a.b")).isEqualTo("shared.proc")
    }
}
