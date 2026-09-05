package com.unique.core.vam

import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.diagnostics.Diagnostics

/**
 * Reads and rebuilds the list wrapper the package and activity AIDLs return.
 *
 * `IPackageManager.getInstalledPackages` and `IPackageManager.queryIntentActivities` have
 * returned `android.content.pm.ParceledListSlice` since API 21, and a shim that rewrites
 * "the result if it is a `List`" therefore never fires on either — the same shape of
 * silent miss as a shim bound to a renamed method. `IActivityManager.getRunningAppProcesses`
 * returns a plain `List`. Both shapes go through here so no caller has to know which.
 */
internal object ParceledLists {

    private const val SLICE = "android.content.pm.ParceledListSlice"

    /** The elements of [value], whichever wrapper it is, or null when it is neither. */
    fun unwrap(value: Any?): List<*>? = when {
        value == null -> null
        value is List<*> -> value
        value.javaClass.name == SLICE -> runCatching {
            value.javaClass.getMethod("getList").invoke(value) as? List<*>
        }.getOrNull()
        else -> null
    }

    /**
     * Puts [list] back into the shape [returnType] declares.
     *
     * Returns null when the shape is unknown, which every caller treats as "leave the
     * platform's answer alone" — a wrong wrapper would be a `ClassCastException` inside
     * the framework, far from here.
     */
    fun wrap(returnType: Class<*>, list: List<*>): Any? = when {
        returnType == List::class.java || returnType == MutableList::class.java -> list
        returnType.name == SLICE -> runCatching {
            returnType.getConstructor(List::class.java).newInstance(list)
        }.getOrElse {
            Diagnostics.event(
                DiagChannel.HOOK, DiagLevel.WARN, "PARCELED_SLICE_REBUILD_FAILED",
                mapOf("type" to returnType.name, "error" to it.toString()),
            )
            null
        }
        else -> null
    }
}
