package com.unique.core.vam

import android.content.res.Resources
import android.os.Bundle
import android.util.TypedValue
import com.unique.core.common.apk.BinaryXml
import com.unique.core.common.apk.MetaDataEntry
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * Builds the `metaData` `Bundle` the platform would have put on a `ComponentInfo`.
 *
 * Left empty, this is not a cosmetic gap. `GoogleApiAvailability` reads one integer out of
 * it and throws when it is missing, on a worker thread, during the first Firebase or
 * Play-services call an app makes:
 *
 * ```
 * IllegalStateException: A required meta-data tag in your app's AndroidManifest.xml does
 *     not exist.  You must have the following declaration within the <application>
 *     element: <meta-data android:name="com.google.android.gms.version" .../>
 * ```
 *
 * The app declares it; UNIQUE simply never handed it over. Everything else that reads
 * meta-data — Firebase's initialisation provider, WorkManager, ad SDKs, Facebook's app id,
 * `android.app.lib_name` for a NativeActivity — failed the same way and quieter.
 *
 * ## Why the resource is resolved here and not at parse time
 *
 * `android:value="@integer/google_play_services_version"` compiles to a *reference*.
 * `PackageParser` reads it through a `TypedArray` built from the app's own `Resources`, so
 * what lands in the `Bundle` is the resolved `int`, not the reference. Only a virtual
 * process has those resources, which is why [MetaDataEntry] carries the compiled type and
 * datum and this is where they become values.
 */
internal object GuestMetaData {

    /**
     * The `Bundle`, or null when the component declares no meta-data.
     *
     * Null rather than an empty `Bundle` on purpose: the platform leaves `metaData` null
     * for a component with no `<meta-data>`, and an app that tests `metaData != null`
     * before reading should see what it would see if it were installed.
     */
    fun bundle(entries: List<MetaDataEntry>, resources: Resources?): Bundle? {
        if (entries.isEmpty()) return null
        val bundle = Bundle(entries.size)
        for (entry in entries) {
            // `android:resource` wins over `android:value`, as it does in PackageParser,
            // and it is stored as the id itself — the app is expected to resolve it.
            if (entry.resourceId != 0) {
                bundle.putInt(entry.name, entry.resourceId)
                continue
            }
            put(bundle, entry, resources)
        }
        return bundle
    }

    private fun put(bundle: Bundle, entry: MetaDataEntry, resources: Resources?) {
        when (entry.valueType) {
            BinaryXml.TYPE_NULL -> Unit
            BinaryXml.TYPE_STRING -> bundle.putString(entry.name, entry.valueString)
            BinaryXml.TYPE_INT_BOOLEAN -> bundle.putBoolean(entry.name, entry.valueData != 0)
            BinaryXml.TYPE_FLOAT ->
                bundle.putFloat(entry.name, java.lang.Float.intBitsToFloat(entry.valueData))
            BinaryXml.TYPE_REFERENCE -> putReference(bundle, entry, resources)
            else -> bundle.putInt(entry.name, entry.valueData)
        }
    }

    /**
     * Resolves `@integer/x`, `@string/x` or `@bool/x` the way a `TypedArray` would.
     *
     * A reference that cannot be resolved is stored as the id. That is what the platform
     * does for `android:resource`, it is the only answer that loses no information, and it
     * keeps a missing resource from removing the key entirely — which is the difference
     * between an app reading a wrong number and an app throwing.
     */
    private fun putReference(bundle: Bundle, entry: MetaDataEntry, resources: Resources?) {
        val id = entry.valueData
        if (resources == null || id == 0) {
            // Reported, because the difference between "the id" and "the value" is
            // invisible in the bundle and decides whether Play services throws.
            if (id != 0) {
                Diagnostics.warn(
                    DiagChannel.PROCESS, "META_DATA_NOT_RESOLVED",
                    mapOf(
                        "name" to entry.name,
                        "id" to "0x${Integer.toHexString(id)}",
                        "reason" to "the guest's resources were not available yet",
                    ),
                )
            }
            bundle.putInt(entry.name, id)
            return
        }
        val value = TypedValue()
        val resolved = runCatching {
            resources.getValue(id, value, true)
            true
        }.getOrElse {
            Diagnostics.warn(
                DiagChannel.PROCESS, "META_DATA_UNRESOLVED",
                mapOf("name" to entry.name, "id" to "0x${Integer.toHexString(id)}"),
            )
            false
        }
        if (!resolved) {
            bundle.putInt(entry.name, id)
            return
        }
        when {
            value.type == TypedValue.TYPE_STRING ->
                bundle.putString(entry.name, value.string?.toString())
            value.type == TypedValue.TYPE_INT_BOOLEAN -> bundle.putBoolean(entry.name, value.data != 0)
            value.type == TypedValue.TYPE_FLOAT -> bundle.putFloat(entry.name, value.float)
            value.type >= TypedValue.TYPE_FIRST_INT && value.type <= TypedValue.TYPE_LAST_INT ->
                bundle.putInt(entry.name, value.data)
            else -> bundle.putInt(entry.name, id)
        }
    }
}
