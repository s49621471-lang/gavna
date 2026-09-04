package com.unique.core.compat

import android.content.Context
import com.unique.core.common.compat.CompatFlag
import com.unique.core.common.compat.CompatibilityProfile
import com.unique.core.common.compat.CompatibilityResolver
import com.unique.core.common.compat.GoogleFlow
import com.unique.core.common.compat.GoogleMode
import com.unique.core.common.compat.SupportLevel
import com.unique.core.common.compat.Workaround
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loads the compatibility database.
 *
 * Two layers: a database shipped as an asset, and a local file a user or support session
 * can drop in to fix an app without waiting for an app update. That is the whole reason
 * the database is data rather than code — and it is why `packageName ==` comparisons are
 * forbidden anywhere outside this module (ARCHITECTURE.md section 18, rule 3).
 *
 * Unknown flag and mode names are skipped with the entry kept, so a database written for
 * a newer UNIQUE still loads on an older one.
 */
object CompatDatabase {

    private const val ASSET = "compat/compatibility.json"
    private const val LOCAL = "compat-overrides.json"

    @Volatile private var resolver: CompatibilityResolver? = null

    /** Names that failed to parse, surfaced in Diagnostics rather than silently dropped. */
    @Volatile var unknownTokens: List<String> = emptyList()
        private set

    @Synchronized
    fun load(context: Context): CompatibilityResolver {
        resolver?.let { return it }
        val unknown = mutableListOf<String>()

        val shipped = runCatching {
            context.assets.open(ASSET).use { parse(it.readBytes().decodeToString(), unknown) }
        }.getOrDefault(emptyList())

        val localFile = File(context.filesDir, LOCAL)
        val local = if (localFile.isFile) {
            runCatching { parse(localFile.readText(), unknown) }.getOrDefault(emptyList())
        } else emptyList()

        unknownTokens = unknown.distinct()
        return CompatibilityResolver(shipped, local).also { resolver = it }
    }

    /** Test seam; also used when the asset is absent in a debug build. */
    @Synchronized
    fun loadFrom(shipped: List<CompatibilityProfile>, local: List<CompatibilityProfile> = emptyList()) {
        resolver = CompatibilityResolver(shipped, local)
    }

    fun resolve(packageName: String, versionCode: Long): CompatibilityProfile =
        resolver?.resolve(packageName, versionCode)
            ?: CompatibilityProfile(packageName = packageName)

    internal fun parse(json: String, unknown: MutableList<String>): List<CompatibilityProfile> {
        val root = JSONObject(json)
        val array = root.optJSONArray("profiles") ?: JSONArray()
        val out = ArrayList<CompatibilityProfile>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val pkg = o.optString("package").takeIf { it.isNotEmpty() } ?: continue
            out += CompatibilityProfile(
                packageName = pkg,
                minVersionCode = o.optLong("minVersionCode", -1).takeIf { it >= 0 },
                maxVersionCode = o.optLong("maxVersionCode", -1).takeIf { it >= 0 },
                flags = o.optJSONArray("flags").toStringList()
                    .mapNotNull { name -> enumOrNull<CompatFlag>(name, unknown) }.toSet(),
                googlePolicy = o.optJSONObject("google")?.let { g ->
                    g.keys().asSequence().mapNotNull { key ->
                        val flow = enumOrNull<GoogleFlow>(key, unknown) ?: return@mapNotNull null
                        val mode = enumOrNull<GoogleMode>(g.optString(key), unknown) ?: return@mapNotNull null
                        flow to mode
                    }.toMap()
                } ?: emptyMap(),
                workarounds = o.optJSONArray("workarounds").toObjectList().map { w ->
                    Workaround(
                        id = w.optString("id"),
                        description = w.optString("description"),
                        params = w.optJSONObject("params")?.let { p ->
                            p.keys().asSequence().associateWith { p.optString(it) }
                        } ?: emptyMap(),
                    )
                },
                support = enumOrNull<SupportLevel>(o.optString("support"), unknown) ?: SupportLevel.UNKNOWN,
                note = o.optString("note").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String, unknown: MutableList<String>): E? {
        if (name.isEmpty()) return null
        return runCatching { enumValueOf<E>(name.uppercase()) }.getOrElse {
            unknown += "${E::class.simpleName}.$name"
            null
        }
    }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }

    private fun JSONArray?.toObjectList(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
