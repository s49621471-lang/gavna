package com.unique.core.common.elf

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Machine types UNIQUE cares about. */
enum class ElfMachine(val value: Int) {
    AARCH64(183), ARM(40), X86_64(62), X86(3), RISCV(243), UNKNOWN(-1);

    companion object {
        fun of(v: Int) = entries.firstOrNull { it.value == v } ?: UNKNOWN
    }
}

/**
 * Result of inspecting a shared object.
 *
 * [maxPageSize] is the property that decides whether the library will load at all on an
 * Android 15/16 device configured with 16 KB pages: the loader requires every PT_LOAD
 * segment to be aligned to at least the system page size. Detecting this at *import*
 * time is the difference between an honest "this app cannot run on this device" and a
 * mystifying `dlopen` failure at first launch.
 */
data class ElfInfo(
    val is64Bit: Boolean,
    val machine: ElfMachine,
    val littleEndian: Boolean,
    /** Minimum p_align across PT_LOAD segments, i.e. the largest page size this .so supports. */
    val maxPageSize: Long,
    val neededLibraries: List<String>,
    val soName: String?,
) {
    val isArm64: Boolean get() = is64Bit && machine == ElfMachine.AARCH64

    /** True when this library can be mapped on a device with the given page size. */
    fun loadableWithPageSize(pageSize: Long): Boolean = maxPageSize >= pageSize

    val supports16KiBPages: Boolean get() = loadableWithPageSize(16384)
}

class ElfException(message: String) : Exception(message)

/**
 * Minimal ELF reader for the checks UNIQUE performs on imported native libraries.
 *
 * Only the ELF header, the program headers and the dynamic table are read — enough for
 * ABI validation, 16 KB page-size validation, and dependency listing for diagnostics.
 * Section headers are deliberately not parsed: they are frequently stripped, and nothing
 * here needs them.
 */
object ElfInspector {

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_SONAME = 14L

    /** Reads at most [LIMIT] bytes; shared objects put all of this near the start. */
    private const val LIMIT = 4 * 1024 * 1024

    fun inspect(bytes: ByteArray): ElfInfo {
        if (bytes.size < 64) throw ElfException("too small to be an ELF file (${bytes.size} bytes)")
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) throw ElfException("bad ELF magic")

        val is64 = bytes[4].toInt() == 2
        val little = bytes[5].toInt() == 1
        val buf = ByteBuffer.wrap(bytes)
            .order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)

        val machine = ElfMachine.of(buf.getShort(18).toInt() and 0xFFFF)

        val phoff: Long
        val phentsize: Int
        val phnum: Int
        if (is64) {
            phoff = buf.getLong(32)
            phentsize = buf.getShort(54).toInt() and 0xFFFF
            phnum = buf.getShort(56).toInt() and 0xFFFF
        } else {
            phoff = buf.getInt(28).toLong() and 0xFFFFFFFFL
            phentsize = buf.getShort(42).toInt() and 0xFFFF
            phnum = buf.getShort(44).toInt() and 0xFFFF
        }

        var minLoadAlign = Long.MAX_VALUE
        var dynOffset = -1L
        var dynSize = 0L
        val loads = ArrayList<LoadSegment>()

        for (i in 0 until phnum) {
            val at = (phoff + i.toLong() * phentsize).toInt()
            if (at < 0 || at + phentsize > bytes.size) break
            val type = buf.getInt(at)
            val offset: Long; val vaddr: Long; val filesz: Long; val align: Long
            if (is64) {
                offset = buf.getLong(at + 8)
                vaddr = buf.getLong(at + 16)
                filesz = buf.getLong(at + 32)
                align = buf.getLong(at + 48)
            } else {
                offset = buf.getInt(at + 4).toLong() and 0xFFFFFFFFL
                vaddr = buf.getInt(at + 8).toLong() and 0xFFFFFFFFL
                filesz = buf.getInt(at + 16).toLong() and 0xFFFFFFFFL
                align = buf.getInt(at + 28).toLong() and 0xFFFFFFFFL
            }
            when (type) {
                PT_LOAD -> {
                    if (align > 0) minLoadAlign = minOf(minLoadAlign, align)
                    loads += LoadSegment(offset, vaddr, filesz)
                }
                PT_DYNAMIC -> { dynOffset = offset; dynSize = filesz }
            }
        }
        if (minLoadAlign == Long.MAX_VALUE) minLoadAlign = 0

        var needed: List<String> = emptyList()
        var soName: String? = null
        if (dynOffset in 0 until bytes.size.toLong()) {
            val dyn = readDynamic(buf, bytes.size, dynOffset, dynSize, is64)
            val strtabVaddr = dyn.firstOrNull { it.tag == DT_STRTAB }?.value
            if (strtabVaddr != null) {
                val strtabOff = vaddrToOffset(loads, strtabVaddr)
                if (strtabOff != null) {
                    needed = dyn.filter { it.tag == DT_NEEDED }
                        .mapNotNull { cstring(bytes, strtabOff + it.value) }
                    soName = dyn.firstOrNull { it.tag == DT_SONAME }
                        ?.let { cstring(bytes, strtabOff + it.value) }
                }
            }
        }

        return ElfInfo(is64, machine, little, minLoadAlign, needed, soName)
    }

    fun inspect(input: InputStream): ElfInfo {
        val bytes = input.readNBytes(LIMIT)
        return inspect(bytes)
    }

    private data class LoadSegment(val offset: Long, val vaddr: Long, val filesz: Long)
    private data class DynEntry(val tag: Long, val value: Long)

    private fun readDynamic(
        buf: ByteBuffer, size: Int, offset: Long, count: Long, is64: Boolean,
    ): List<DynEntry> {
        val entrySize = if (is64) 16 else 8
        val out = ArrayList<DynEntry>()
        var at = offset
        val end = minOf(size.toLong(), if (count > 0) offset + count else size.toLong())
        while (at + entrySize <= end) {
            val tag: Long; val value: Long
            if (is64) {
                tag = buf.getLong(at.toInt()); value = buf.getLong(at.toInt() + 8)
            } else {
                tag = buf.getInt(at.toInt()).toLong(); value = buf.getInt(at.toInt() + 4).toLong() and 0xFFFFFFFFL
            }
            if (tag == DT_NULL) break
            out += DynEntry(tag, value)
            at += entrySize
        }
        return out
    }

    private fun vaddrToOffset(loads: List<LoadSegment>, vaddr: Long): Long? =
        loads.firstOrNull { vaddr >= it.vaddr && vaddr < it.vaddr + it.filesz }
            ?.let { vaddr - it.vaddr + it.offset }

    private fun cstring(bytes: ByteArray, at: Long): String? {
        if (at < 0 || at >= bytes.size) return null
        var end = at.toInt()
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        if (end == at.toInt()) return null
        return String(bytes, at.toInt(), end - at.toInt(), Charsets.UTF_8)
    }
}
