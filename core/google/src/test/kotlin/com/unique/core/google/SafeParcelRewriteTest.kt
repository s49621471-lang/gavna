package com.unique.core.google

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/**
 * Walking a Play services request without an Android runtime.
 *
 * `Parcel` is not available in a JVM test, so the parcel is built here as bytes in the
 * exact layout `SafeParcelWriter` produces and the walker is given a reader over them.
 * That is the value of splitting the walk out of `GmsBrokerBinder`: the part that can
 * silently corrupt a request is the part that decides where fields begin and end, and
 * this is the only place it can be checked without a phone.
 *
 * The rule the assertions enforce is one-directional. A parcel that is not understood
 * must produce null, because the caller's answer to null is "send exactly what the app
 * wrote" — the refusal that was happening anyway. A parcel that is *mis*understood and
 * accepted would send Play services something malformed, which is worse than the failure
 * being fixed.
 */
class SafeParcelRewriteTest {

    /** Builds parcel bytes the way `SafeParcelWriter` does, for a test to walk. */
    private class Writer {
        private val out = Bytes()

        fun int(value: Int) = apply { out.int(value) }

        /** A string field, written the way `SafeParcelWriter.writeString` writes one. */
        fun stringField(id: Int, value: String) = apply {
            out.int(SafeParcelRewrite.variableFieldHeader(id))
            val sizeAt = out.size()
            out.int(0)
            val start = out.size()
            out.string(value)
            out.patch(sizeAt, out.size() - start)
        }

        /** A small fixed-size field, the other shape a field header can take. */
        fun intField(id: Int, value: Int) = apply {
            out.int((4 shl 16) or id)
            out.int(value)
        }

        fun obj(body: Writer.() -> Unit) = apply {
            out.int(SafeParcelRewrite.objectHeader())
            val sizeAt = out.size()
            out.int(0)
            val start = out.size()
            body()
            out.patch(sizeAt, out.size() - start)
        }

        fun bytes(): ByteArray = out.bytes()
    }

    private class Bytes {
        private var buffer = ByteArray(256)
        private var length = 0

        fun size() = length

        fun int(value: Int) {
            ensure(4)
            ByteBuffer.wrap(buffer, length, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
            length += 4
        }

        /** `Parcel.writeString`: length in chars, then UTF-16 plus a terminator, padded. */
        fun string(value: String) {
            int(value.length)
            ensure((value.length + 1) * 2 + 3)
            val chars = ByteBuffer.wrap(buffer, length, (value.length + 1) * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (c in value) chars.putChar(c)
            chars.putChar(0.toChar())
            length += (value.length + 1) * 2
            while (length % 4 != 0) {
                buffer[length] = 0
                length++
            }
        }

        fun patch(at: Int, value: Int) {
            ByteBuffer.wrap(buffer, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        }

        fun bytes(): ByteArray = buffer.copyOf(length)

        private fun ensure(more: Int) {
            while (length + more > buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        }
    }

    private fun readerOver(bytes: ByteArray): (Int) -> Int = { at ->
        ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /** A request shaped like the real `GetServiceRequest`: ints, then the calling package. */
    private fun request(callingPackage: String, leadingBytes: Int = 12): ByteArray =
        Writer()
            // Stands in for the interface token and the callbacks binder, which the walk
            // must step over without understanding either.
            .apply { repeat(leadingBytes / 4) { int(0x00610062) } }
            .obj {
                intField(1, 4)
                stringField(4, callingPackage)
                intField(6, 0)
            }
            .bytes()

    @Test fun `the calling package is found by value, not by field number`() {
        val bytes = request("com.gordey.standarling")
        val read = readerOver(bytes)
        val offset = SafeParcelRewrite.candidateOffsets(bytes.size, read).single()
        val located = SafeParcelRewrite.locate(offset, bytes.size, read)!!
        assertThat(located.fields.map { it.id }).containsExactly(1, 4, 6).inOrder()
        // Every field's extent is exact: the rebuild copies by these offsets, so an
        // off-by-four here is a corrupt request rather than a wrong answer.
        assertThat(located.fields.last().end).isEqualTo(located.end)
    }

    @Test fun `the object is found wherever the arguments before it happen to end`() {
        // The argument list differs per method and per version of the library, so the
        // walk must not depend on knowing how much precedes the object.
        for (leading in listOf(0, 4, 8, 12, 40)) {
            val bytes = request("com.example.app", leadingBytes = leading)
            assertThat(SafeParcelRewrite.candidateOffsets(bytes.size, readerOver(bytes)))
                .contains(leading)
        }
    }

    @Test fun `a header that is not a SafeParcelable is refused`() {
        // Four bytes can coincide with the magic. What cannot coincide is a field walk
        // that lands exactly on the declared end, so that is the check that decides.
        val bytes = Writer()
            .int(SafeParcelRewrite.objectHeader())
            .int(64) // claims 64 bytes of fields and provides none
            .bytes()
        assertThat(SafeParcelRewrite.locate(0, bytes.size, readerOver(bytes))).isNull()
    }

    @Test fun `a field running past the end of the object is refused`() {
        val bytes = Writer()
            .int(SafeParcelRewrite.objectHeader())
            .int(12)
            .int(SafeParcelRewrite.variableFieldHeader(4))
            .int(9999) // a size far beyond the object
            .int(0)
            .bytes()
        assertThat(SafeParcelRewrite.locate(0, bytes.size, readerOver(bytes))).isNull()
    }

    @Test fun `a negative size is refused rather than wrapped around`() {
        val bytes = Writer()
            .int(SafeParcelRewrite.objectHeader())
            .int(-1)
            .bytes()
        assertThat(SafeParcelRewrite.locate(0, bytes.size, readerOver(bytes))).isNull()
    }

    @Test fun `a walk that ends short of the declared end is refused`() {
        // The object declares more body than its fields account for. Accepting it would
        // mean copying a region whose contents were never understood.
        val bytes = Writer().obj { intField(1, 7) }.bytes()
        val stretched = bytes.copyOf()
        ByteBuffer.wrap(stretched, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(12)
        assertThat(SafeParcelRewrite.locate(0, stretched.size, readerOver(stretched))).isNull()
    }

    @Test fun `both field header shapes are read`() {
        // A small field packs its size into the header; a variable one puts it in the
        // next int. A reader that knows only one shape mis-reads every request.
        val bytes = Writer().obj {
            intField(2, 42)
            stringField(3, "x")
        }.bytes()
        val located = SafeParcelRewrite.locate(0, bytes.size, readerOver(bytes))!!
        assertThat(located.fields).hasSize(2)
        assertThat(located.fields[0].size).isEqualTo(4)
        assertThat(located.fields[0].dataStart - located.fields[0].headerStart).isEqualTo(4)
        assertThat(located.fields[1].dataStart - located.fields[1].headerStart).isEqualTo(8)
    }

    @Test fun `an empty object walks to nothing rather than failing`() {
        val bytes = Writer().obj { }.bytes()
        val located = SafeParcelRewrite.locate(0, bytes.size, readerOver(bytes))!!
        assertThat(located.fields).isEmpty()
        assertThat(located.end).isEqualTo(located.bodyStart)
    }

    @Test fun `an offset outside the parcel is refused rather than read`() {
        val bytes = Writer().obj { intField(1, 1) }.bytes()
        assertThat(SafeParcelRewrite.locate(bytes.size - 2, bytes.size, readerOver(bytes)))
            .isNull()
        assertThat(SafeParcelRewrite.locate(-4, bytes.size, readerOver(bytes))).isNull()
    }

    @Test fun `the header encoders and decoders agree`() {
        for (id in listOf(1, 4, 255, 20293, 0xffff)) {
            assertThat(SafeParcelRewrite.fieldId(SafeParcelRewrite.variableFieldHeader(id)))
                .isEqualTo(id)
            assertThat(SafeParcelRewrite.declaredSize(SafeParcelRewrite.variableFieldHeader(id)))
                .isEqualTo(SafeParcelRewrite.LARGE)
        }
        assertThat(SafeParcelRewrite.fieldId(SafeParcelRewrite.objectHeader()))
            .isEqualTo(SafeParcelRewrite.OBJECT_HEADER_ID)
    }
}
