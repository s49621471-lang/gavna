package com.unique.core.common.elf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verified against real NDK r27 output for arm64-v8a. */
class ElfInspectorTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) {
            "missing fixture $name"
        }.use { it.readBytes() }

    @Test fun `recognises an arm64 shared object`() {
        val info = ElfInspector.inspect(fixture("libprobe16k.so"))
        assertThat(info.is64Bit).isTrue()
        assertThat(info.machine).isEqualTo(ElfMachine.AARCH64)
        assertThat(info.littleEndian).isTrue()
        assertThat(info.isArm64).isTrue()
    }

    @Test fun `reads the soname`() {
        assertThat(ElfInspector.inspect(fixture("libprobe16k.so")).soName)
            .isEqualTo("libprobe16k.so")
    }

    @Test fun `lists dynamic dependencies`() {
        val info = ElfInspector.inspect(fixture("libprobe16k.so"))
        assertThat(info.neededLibraries).contains("libc.so")
    }

    @Test fun `detects a 16 KiB aligned library`() {
        val info = ElfInspector.inspect(fixture("libprobe16k.so"))
        assertThat(info.maxPageSize).isEqualTo(16384L)
        assertThat(info.supports16KiBPages).isTrue()
        assertThat(info.loadableWithPageSize(4096)).isTrue()
    }

    @Test fun `detects a 4 KiB library that cannot load on a 16 KiB device`() {
        val info = ElfInspector.inspect(fixture("libprobe4k.so"))
        assertThat(info.maxPageSize).isEqualTo(4096L)
        assertThat(info.supports16KiBPages).isFalse()
        assertThat(info.loadableWithPageSize(4096)).isTrue()
    }

    @Test fun `rejects a non-elf payload`() {
        val e = runCatching { ElfInspector.inspect(ByteArray(128)) }.exceptionOrNull()
        assertThat(e).isInstanceOf(ElfException::class.java)
    }

    @Test fun `rejects a truncated file rather than reading out of bounds`() {
        val e = runCatching { ElfInspector.inspect(ByteArray(16)) }.exceptionOrNull()
        assertThat(e).isInstanceOf(ElfException::class.java)
    }
}
