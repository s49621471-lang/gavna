package com.unique.core.common.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoder for Android's binary XML (`AndroidManifest.xml` inside an APK).
 *
 * This exists because UNIQUE must read a package's manifest *without* asking the
 * platform to parse it: the platform only parses manifests of packages it is installing,
 * and virtual packages are never installed. `PackageParser`/`ParsingPackageUtils` are
 * hidden API with a signature that changes every release, so relying on them is exactly
 * the fragility this project is trying to avoid.
 *
 * Format reference: `ResourceTypes.h` in AOSP. Only the subset needed for manifests is
 * implemented; unknown chunk types are skipped by their declared size rather than
 * treated as an error, which is what keeps this forward-compatible.
 */
internal object BinaryXml {

    // ResChunk_header.type
    const val RES_NULL_TYPE = 0x0000
    const val RES_STRING_POOL_TYPE = 0x0001
    const val RES_XML_TYPE = 0x0003
    const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    const val RES_XML_START_ELEMENT_TYPE = 0x0102
    const val RES_XML_END_ELEMENT_TYPE = 0x0103
    const val RES_XML_CDATA_TYPE = 0x0104
    const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

    // Res_value.dataType
    const val TYPE_NULL = 0x00
    const val TYPE_REFERENCE = 0x01
    const val TYPE_ATTRIBUTE = 0x02
    const val TYPE_STRING = 0x03
    const val TYPE_FLOAT = 0x04
    const val TYPE_DIMENSION = 0x05
    const val TYPE_FRACTION = 0x06
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_INT_BOOLEAN = 0x12
    const val TYPE_FIRST_COLOR_INT = 0x1c
    const val TYPE_LAST_COLOR_INT = 0x1f

    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
}

class BinaryXmlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A decoded attribute of a start-element event. */
data class XmlAttribute(
    val namespace: String?,
    val name: String,
    /** Android attribute resource id when the manifest was compiled with one, else 0. */
    val resourceId: Int,
    val dataType: Int,
    val rawData: Int,
    val stringValue: String?,
) {
    /** Textual form, using the same rules `aapt2 dump xmltree` uses. */
    fun asString(): String? = when (dataType) {
        BinaryXml.TYPE_STRING -> stringValue
        BinaryXml.TYPE_NULL -> null
        BinaryXml.TYPE_INT_BOOLEAN -> (rawData != 0).toString()
        BinaryXml.TYPE_INT_HEX -> "0x" + Integer.toHexString(rawData)
        BinaryXml.TYPE_REFERENCE -> "@" + Integer.toHexString(rawData)
        BinaryXml.TYPE_FLOAT -> java.lang.Float.intBitsToFloat(rawData).toString()
        else -> rawData.toString()
    }

    fun asInt(default: Int = 0): Int = when (dataType) {
        BinaryXml.TYPE_STRING -> stringValue?.trim()?.toIntOrNull() ?: default
        BinaryXml.TYPE_NULL -> default
        else -> rawData
    }

    fun asBoolean(default: Boolean = false): Boolean = when (dataType) {
        BinaryXml.TYPE_INT_BOOLEAN -> rawData != 0
        BinaryXml.TYPE_STRING -> stringValue?.trim()?.toBooleanStrictOrNull() ?: default
        BinaryXml.TYPE_NULL -> default
        else -> rawData != 0
    }
}

/** A start-element event with its attributes already decoded. */
class XmlElement(
    val name: String,
    val namespace: String?,
    val attributes: List<XmlAttribute>,
    val depth: Int,
) {
    /**
     * Look an attribute up by its android resource id first and by local name second.
     *
     * Resource id takes priority because AAPT2 is allowed to blank out attribute names in
     * the string pool (and obfuscators routinely do), while the resource id in the
     * resource map survives. Falling back to the name keeps hand-written and
     * legacy-tooling manifests working.
     */
    fun attr(resourceId: Int, localName: String): XmlAttribute? =
        attributes.firstOrNull { it.resourceId == resourceId && resourceId != 0 }
            ?: attributes.firstOrNull { it.name == localName && it.isAndroidNs() }
            ?: attributes.firstOrNull { it.name == localName }

    fun attrByName(localName: String): XmlAttribute? = attributes.firstOrNull { it.name == localName }

    private fun XmlAttribute.isAndroidNs() =
        namespace == null || namespace == BinaryXml.ANDROID_NAMESPACE
}

/**
 * Pull-style reader over a binary XML document.
 *
 * Usage is intentionally minimal — [forEachElement] walks start elements in document
 * order with a maintained depth, which is all the manifest reader needs.
 */
class BinaryXmlReader(bytes: ByteArray) {

    private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private lateinit var strings: StringPool
    /** string-pool index -> android attribute resource id, from RES_XML_RESOURCE_MAP_TYPE. */
    private var resourceMap: IntArray = IntArray(0)

    init {
        if (bytes.size < 8) throw BinaryXmlException("document too small (${bytes.size} bytes)")
        val type = buf.short.toInt() and 0xFFFF
        val headerSize = buf.short.toInt() and 0xFFFF
        val size = buf.int
        if (type != BinaryXml.RES_XML_TYPE) {
            throw BinaryXmlException("not a binary XML document (chunk type 0x${type.toString(16)})")
        }
        if (size > bytes.size) {
            throw BinaryXmlException("declared size $size exceeds buffer ${bytes.size}")
        }
        buf.position(headerSize)
    }

    /** Walks the document, invoking [block] for every start element. */
    fun forEachElement(block: (XmlElement) -> Unit) {
        var depth = 0
        val namespaces = HashMap<Int, String>() // prefix index -> uri, unused but tracked for completeness
        while (buf.remaining() >= 8) {
            val chunkStart = buf.position()
            val type = buf.short.toInt() and 0xFFFF
            val headerSize = buf.short.toInt() and 0xFFFF
            val chunkSize = buf.int
            if (chunkSize < 8 || chunkStart + chunkSize > buf.limit()) {
                throw BinaryXmlException("corrupt chunk at $chunkStart (size=$chunkSize)")
            }
            when (type) {
                BinaryXml.RES_STRING_POOL_TYPE -> {
                    strings = StringPool.parse(buf, chunkStart, headerSize, chunkSize)
                }
                BinaryXml.RES_XML_RESOURCE_MAP_TYPE -> {
                    val count = (chunkSize - headerSize) / 4
                    buf.position(chunkStart + headerSize)
                    resourceMap = IntArray(count) { buf.int }
                }
                BinaryXml.RES_XML_START_NAMESPACE_TYPE -> {
                    buf.position(chunkStart + headerSize)
                    val prefix = buf.int
                    val uri = buf.int
                    if (uri >= 0) namespaces[prefix] = stringAt(uri) ?: ""
                }
                BinaryXml.RES_XML_START_ELEMENT_TYPE -> {
                    block(readStartElement(chunkStart, headerSize, depth))
                    depth++
                }
                BinaryXml.RES_XML_END_ELEMENT_TYPE -> depth--
                else -> Unit // RES_XML_CDATA_TYPE, end-namespace, and anything future: skipped by size
            }
            buf.position(chunkStart + chunkSize)
        }
    }

    private fun readStartElement(chunkStart: Int, headerSize: Int, depth: Int): XmlElement {
        buf.position(chunkStart + headerSize)
        val ns = buf.int
        val nameIdx = buf.int
        val attributeStart = buf.short.toInt() and 0xFFFF
        val attributeSize = buf.short.toInt() and 0xFFFF
        val attributeCount = buf.short.toInt() and 0xFFFF
        // idIndex / classIndex / styleIndex follow; not needed here.

        val attrs = ArrayList<XmlAttribute>(attributeCount)
        val base = chunkStart + headerSize + attributeStart
        for (i in 0 until attributeCount) {
            val p = base + i * attributeSize
            if (p + 20 > buf.limit()) break
            buf.position(p)
            val attrNs = buf.int
            val attrName = buf.int
            val rawValue = buf.int
            // Res_value: size(u16) res0(u8) dataType(u8) data(u32)
            buf.short // size
            buf.get()  // res0
            val dataType = buf.get().toInt() and 0xFF
            val data = buf.int

            val resId = if (attrName >= 0 && attrName < resourceMap.size) resourceMap[attrName] else 0
            val localName = stringAt(attrName) ?: ""
            val strValue = when {
                dataType == BinaryXml.TYPE_STRING -> stringAt(data)
                rawValue >= 0 -> stringAt(rawValue)
                else -> null
            }
            attrs += XmlAttribute(
                namespace = stringAt(attrNs),
                name = localName,
                resourceId = resId,
                dataType = dataType,
                rawData = data,
                stringValue = strValue,
            )
        }
        return XmlElement(stringAt(nameIdx) ?: "", stringAt(ns), attrs, depth)
    }

    private fun stringAt(index: Int): String? =
        if (index < 0 || !this::strings.isInitialized) null else strings.get(index)
}

/** `ResStringPool` decoder, supporting both the UTF-16 and UTF-8 encodings. */
internal class StringPool private constructor(
    private val values: Array<String?>,
) {
    fun get(index: Int): String? = values.getOrNull(index)

    companion object {
        private const val UTF8_FLAG = 1 shl 8

        fun parse(buf: ByteBuffer, chunkStart: Int, headerSize: Int, chunkSize: Int): StringPool {
            buf.position(chunkStart + 8)
            val stringCount = buf.int
            @Suppress("UNUSED_VARIABLE") val styleCount = buf.int
            val flags = buf.int
            val stringsStart = buf.int
            @Suppress("UNUSED_VARIABLE") val stylesStart = buf.int
            val utf8 = (flags and UTF8_FLAG) != 0

            if (stringCount < 0 || stringCount > (chunkSize / 4) + 1) {
                throw BinaryXmlException("implausible string count $stringCount")
            }
            buf.position(chunkStart + headerSize)
            val offsets = IntArray(stringCount) { buf.int }
            val dataStart = chunkStart + stringsStart
            val dataEnd = chunkStart + chunkSize

            val values = arrayOfNulls<String>(stringCount)
            for (i in 0 until stringCount) {
                val at = dataStart + offsets[i]
                if (at < dataStart || at >= dataEnd) continue
                values[i] = runCatching {
                    if (utf8) readUtf8(buf, at, dataEnd) else readUtf16(buf, at, dataEnd)
                }.getOrNull()
            }
            return StringPool(values)
        }

        /** UTF-16 entry: u16 length (0x8000-extended), then `length` UTF-16 code units. */
        private fun readUtf16(buf: ByteBuffer, at: Int, end: Int): String {
            buf.position(at)
            var len = buf.short.toInt() and 0xFFFF
            if (len and 0x8000 != 0) {
                len = ((len and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
            }
            val byteLen = len * 2
            if (at + byteLen > end) throw BinaryXmlException("utf16 string overruns pool")
            val chars = CharArray(len) { buf.short.toInt().toChar() }
            return String(chars)
        }

        /**
         * UTF-8 entry: u8 UTF-16 length, u8 UTF-8 byte length (each 0x80-extended),
         * then the bytes. The declared UTF-16 length is informational and deliberately
         * ignored — the byte length is authoritative.
         */
        private fun readUtf8(buf: ByteBuffer, at: Int, end: Int): String {
            buf.position(at)
            var u16len = buf.get().toInt() and 0xFF
            if (u16len and 0x80 != 0) {
                u16len = ((u16len and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
            }
            var u8len = buf.get().toInt() and 0xFF
            if (u8len and 0x80 != 0) {
                u8len = ((u8len and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
            }
            if (buf.position() + u8len > end) throw BinaryXmlException("utf8 string overruns pool")
            val bytes = ByteArray(u8len)
            buf.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
