package com.unique.core.google

/**
 * Finds and replaces the calling package inside a marshalled Play services request.
 *
 * ## The one call that decides whether Google works at all
 *
 * Every Play services client, whatever API it is for, starts the same way:
 *
 * ```java
 * GetServiceRequest request = new GetServiceRequest(...);
 * request.callingPackage = context.getPackageName();
 * broker.getService(callbacks, request);
 * ```
 *
 * `com.google.android.gms` receives that over Binder, takes `Binder.getCallingUid()`,
 * asks the package manager which packages that uid owns, and refuses if the request's
 * calling package is not among them:
 *
 * ```
 * java.lang.SecurityException: Unknown calling package name 'com.gordey.standarling'.
 *     at …common.internal.c.getRemoteService (play-services-basement@@17.4.0:25)
 * ```
 *
 * Inside UNIQUE the uid is always UNIQUE's, so this fails for **every** guest and every
 * Google API — sign-in, Maps, ads, Firebase, Dynamite modules, the advertising ID. It is
 * a single refusal, and everything Google-shaped that has ever failed in this project
 * failed behind it.
 *
 * The uid cannot be changed: it is the kernel's, checked by another process. What *can*
 * be changed is the name in the request, and `com.unique` genuinely is a package that uid
 * owns. So the request is rewritten in flight, and Play services accepts it.
 *
 * ## Rewriting a parcel that cannot be marshalled
 *
 * `Parcel.marshall()` refuses any parcel holding a binder, and this one holds the
 * client's callback interface — so the usual "get the bytes, patch them, put them back"
 * is not available. The parcel is rebuilt instead: `Parcel.appendFrom` copies byte ranges
 * *including* their binder references, so everything except the one string is carried
 * over untouched and only the replaced field is written fresh.
 *
 * ## The format, which is stable and documented enough to walk
 *
 * `GetServiceRequest` is a `SafeParcelable`, and `SafeParcelWriter` has one shape:
 *
 * ```
 * object header   int  0xffff0000 | 20293 , then int size          (back-patched)
 * field           int  (size << 16) | id                           small fields
 *                 int  0xffff0000 | id  , then int size            variable data
 *                 <size bytes>
 * ```
 *
 * That is walked here without knowing a single field number. The field to replace is
 * found *by value* — the one whose contents decode to exactly the guest's package name —
 * because the field's index is Google's private business and changes between versions
 * while the value never does.
 *
 * ## Refusing to guess
 *
 * Every step is checked and any inconsistency abandons the rewrite and leaves the parcel
 * exactly as the app wrote it: a field that runs past the end of the object, a walk that
 * does not land precisely on the object's last byte, an object header that is not where
 * one is expected. A malformed parcel reaching Play services would be a worse failure
 * than the refusal this is fixing, so the bar for touching it at all is that the whole
 * structure was understood.
 */
internal object SafeParcelRewrite {

    /** `SafeParcelWriter`'s object header field id. Google's, and constant for a decade. */
    const val OBJECT_HEADER_ID = 20293

    /** The size marker meaning "the real size is the next int". */
    const val LARGE = 0xffff

    /** A field of a SafeParcelable object, located within a parcel. */
    data class Field(
        val id: Int,
        /** Where this field's header int starts. */
        val headerStart: Int,
        /** Where the field's payload starts. */
        val dataStart: Int,
        val size: Int,
    ) {
        val end: Int get() = dataStart + size
        val totalLength: Int get() = end - headerStart
    }

    /** A located SafeParcelable object and its fields. */
    data class Located(
        /** Where the object's header int starts. */
        val start: Int,
        /** Where the object's body starts, immediately after the back-patched size. */
        val bodyStart: Int,
        val end: Int,
        val fields: List<Field>,
    )

    /** The header int that opens a `SafeParcelWriter` object. */
    fun objectHeader(): Int = (LARGE shl 16) or OBJECT_HEADER_ID

    /** The header int for a variable-size field, whose size follows as its own int. */
    fun variableFieldHeader(id: Int): Int = (LARGE shl 16) or (id and 0xffff)

    fun fieldId(header: Int): Int = header and 0xffff

    /** The size a field header declares, or [LARGE] when the next int carries it. */
    fun declaredSize(header: Int): Int = (header ushr 16) and 0xffff

    /**
     * Walks a `SafeParcelWriter` object whose header int sits at [start].
     *
     * @param readInt reads the 4 bytes at the given offset, big enough to be a whole int.
     * @param limit one past the last readable byte.
     * @return the object with its fields, or null if anything did not add up.
     */
    fun locate(start: Int, limit: Int, readInt: (Int) -> Int): Located? {
        if (start < 0 || start + 8 > limit) return null
        if (readInt(start) != objectHeader()) return null
        val size = readInt(start + 4)
        val bodyStart = start + 8
        val end = bodyStart + size
        // A negative or overlong size is either a false match or a corrupt parcel; both
        // mean "do not touch this".
        if (size < 0 || end > limit) return null

        val fields = ArrayList<Field>(8)
        var at = bodyStart
        while (at < end) {
            if (at + 4 > end) return null
            val header = readInt(at)
            val declared = declaredSize(header)
            val dataStart: Int
            val fieldSize: Int
            if (declared == LARGE) {
                if (at + 8 > end) return null
                fieldSize = readInt(at + 4)
                dataStart = at + 8
            } else {
                fieldSize = declared
                dataStart = at + 4
            }
            if (fieldSize < 0 || dataStart + fieldSize > end) return null
            fields += Field(fieldId(header), at, dataStart, fieldSize)
            at = dataStart + fieldSize
        }
        // Landing anywhere but exactly on the end means the walk was reading something
        // that is not a SafeParcelable, so nothing here is what it looked like.
        if (at != end) return null
        return Located(start, bodyStart, end, fields)
    }

    /**
     * The number of bytes `Parcel.writeString` uses for [value].
     *
     * An int for the length, then UTF-16 with a terminator, padded to a four-byte
     * boundary. Used to skip fields that cannot possibly hold the string being looked
     * for — which matters for more than speed: probing a field that happens to overlap a
     * binder or a file descriptor makes the platform log
     *
     * ```
     * Attempt to read from protected data in Parcel 0xb400007b7b160940
     * ```
     *
     * once per probe. Harmless, since the read returns nothing and the walk carries on,
     * but six of those before every rewrite is noise in a log someone has to read, and a
     * size check removes almost all of them for the cost of an arithmetic expression.
     */
    fun stringFieldSize(value: String): Int {
        val chars = (value.length + 1) * 2
        return 4 + chars + ((4 - chars % 4) % 4)
    }

    /**
     * The offsets at which a `SafeParcelWriter` object header appears in [0, limit).
     *
     * A scan rather than a parse of the argument list, because the arguments differ per
     * method and per version of the library while the object header does not. It cannot
     * collide with text: the header's top two bytes are `0xffff`, and a UTF-16 string of
     * ASCII never produces those.
     */
    fun candidateOffsets(limit: Int, readInt: (Int) -> Int): List<Int> {
        val found = ArrayList<Int>(2)
        var at = 0
        while (at + 8 <= limit) {
            if (readInt(at) == objectHeader()) found += at
            at += 4
        }
        return found
    }
}
