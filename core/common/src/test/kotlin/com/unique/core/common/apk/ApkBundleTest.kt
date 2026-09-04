package com.unique.core.common.apk

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class ApkBundleTest {

    private fun fixture(name: String): File =
        File(checkNotNull(javaClass.classLoader.getResource("fixtures/$name")).toURI())

    private fun bundle() = ApkBundleReader.read(
        listOf(
            fixture("sample-base.apk"),
            fixture("sample-split-abi.apk"),
            fixture("sample-split-feature.apk"),
        )
    )

    @Test fun `identifies the base by manifest, not by filename`() {
        val b = bundle()
        assertThat(b.base.file.name).isEqualTo("sample-base.apk")
        assertThat(b.manifest.packageName).isEqualTo("com.example.sample")
    }

    @Test fun `classifies split kinds`() {
        val parts = bundle().parts.associateBy { it.file.name }
        assertThat(parts.getValue("sample-split-abi.apk").kind).isEqualTo(SplitKind.ABI)
        assertThat(parts.getValue("sample-split-abi.apk").abi).isEqualTo(Abi.ARM64_V8A)
        assertThat(parts.getValue("sample-split-feature.apk").kind).isEqualTo(SplitKind.FEATURE)
    }

    @Test fun `selects the arm64 split and keeps every feature split`() {
        val sel = bundle().select(DeviceSpec.ARM64)
        val kept = sel.keep.map { it.file.name }
        assertThat(kept).containsExactly(
            "sample-base.apk", "sample-split-feature.apk", "sample-split-abi.apk",
        )
        assertThat(sel.drop).isEmpty()
    }

    @Test fun `drops abi splits the device cannot run`() {
        val armOnly = DeviceSpec(listOf(Abi.ARMEABI_V7A), 480, listOf("en"))
        val sel = bundle().select(armOnly)
        assertThat(sel.keep.map { it.file.name }).doesNotContain("sample-split-abi.apk")
        assertThat(sel.drop.single().second).isEqualTo("ABI not selected")
    }

    @Test fun `rejects a set with no base`() {
        val e = runCatching {
            ApkBundleReader.read(listOf(fixture("sample-split-abi.apk")))
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(BinaryXmlException::class.java)
        assertThat(e).hasMessageThat().contains("no base APK")
    }

    @Test fun `abi split token conversion round-trips`() {
        assertThat(Abi.ARM64_V8A.splitToken).isEqualTo("arm64_v8a")
        assertThat(Abi.fromSplitToken("arm64_v8a")).isEqualTo(Abi.ARM64_V8A)
        assertThat(Abi.fromDirName("arm64-v8a")).isEqualTo(Abi.ARM64_V8A)
        assertThat(Abi.supportedSet).containsExactly(Abi.ARM64_V8A)
    }

    @Test fun `density selection picks the smallest bucket at or above the device`() {
        val f = fixture("sample-base.apk")
        val parts = listOf(
            ApkPart(f, null, SplitKind.BASE),
            ApkPart(f, "config.hdpi", SplitKind.DENSITY, density = "hdpi"),
            ApkPart(f, "config.xxhdpi", SplitKind.DENSITY, density = "xxhdpi"),
            ApkPart(f, "config.xxxhdpi", SplitKind.DENSITY, density = "xxxhdpi"),
        )
        val b = ApkBundle(parts, ManifestReader.fromApk(f))
        val chosen = b.select(DeviceSpec(listOf(Abi.ARM64_V8A), 420, listOf("en")))
            .keep.filter { it.kind == SplitKind.DENSITY }
        assertThat(chosen.map { it.density }).containsExactly("xxhdpi")
    }

    @Test fun `unknown config dimensions are kept rather than silently dropped`() {
        val f = fixture("sample-base.apk")
        val m = ManifestReader.fromApk(f)
        val part = ApkBundleReader.classify(f, "config.astc", m)
        assertThat(part.kind).isEqualTo(SplitKind.UNKNOWN)
        val b = ApkBundle(listOf(ApkPart(f, null, SplitKind.BASE), part), m)
        assertThat(b.select(DeviceSpec.ARM64).keep).hasSize(2)
    }
}
