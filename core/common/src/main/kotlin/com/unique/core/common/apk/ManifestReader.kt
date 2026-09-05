package com.unique.core.common.apk

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Turns a binary `AndroidManifest.xml` into an [ApkManifest].
 *
 * The reader is depth-driven rather than recursive-descent so that unknown elements
 * anywhere in the tree are simply ignored: a manifest from a future platform release
 * parses to the same result as today's, minus anything genuinely new. That property is
 * what lets UNIQUE import apps built with tooling newer than itself.
 */
object ManifestReader {

    private const val MANIFEST_ENTRY = "AndroidManifest.xml"

    fun fromApk(apk: File): ApkManifest =
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(MANIFEST_ENTRY)
                ?: throw BinaryXmlException("${apk.name} has no $MANIFEST_ENTRY")
            zip.getInputStream(entry).use { fromStream(it) }
        }

    fun fromStream(input: InputStream): ApkManifest = fromBytes(input.readBytes())

    fun fromBytes(bytes: ByteArray): ApkManifest {
        val reader = BinaryXmlReader(bytes)

        var packageName = ""
        var versionCode = 0L
        var versionCodeMajor = 0L
        var versionName: String? = null
        var minSdk = 1
        var targetSdk = 0
        var sharedUserId: String? = null
        var splitName: String? = null
        var isFeatureSplit = false

        var appClass: String? = null
        var appProcess: String? = null
        var appComponentFactory: String? = null
        var hasCode = true
        var extractNativeLibs: Boolean? = null
        var label: String? = null
        var labelResId = 0
        var iconResId = 0
        var themeResId = 0

        val usesPermissions = LinkedHashSet<String>()
        val declaredPermissions = ArrayList<DeclaredPermission>()
        val components = ArrayList<ComponentEntry>()
        val appMetaData = LinkedHashMap<String, String?>()
        val nativeLibs = LinkedHashSet<String>()

        // Mutable state for the component currently being assembled.
        var cur: ComponentBuilder? = null
        var curFilter: FilterBuilder? = null
        // Depth at which the current component / filter started, so we know when it ends.
        var curDepth = -1
        var filterDepth = -1

        fun flushFilter() {
            val f = curFilter ?: return
            cur?.filters?.add(f.build())
            curFilter = null
            filterDepth = -1
        }

        fun flushComponent() {
            flushFilter()
            val c = cur ?: return
            components += c.build(packageName)
            cur = null
            curDepth = -1
        }

        reader.forEachElement { el ->
            // Close any component/filter whose subtree we have left.
            if (filterDepth >= 0 && el.depth <= filterDepth) flushFilter()
            if (curDepth >= 0 && el.depth <= curDepth) flushComponent()

            when (el.name) {
                "manifest" -> {
                    packageName = el.attrByName("package")?.asString().orEmpty()
                    versionCode = (el.attr(AndroidAttrs.VERSION_CODE, "versionCode")?.asInt(0) ?: 0).toLong() and 0xFFFFFFFFL
                    versionCodeMajor = (el.attrByName("versionCodeMajor")?.asInt(0) ?: 0).toLong()
                    versionName = el.attr(AndroidAttrs.VERSION_NAME, "versionName")?.asString()
                    sharedUserId = el.attr(AndroidAttrs.SHARED_USER_ID, "sharedUserId")?.asString()
                    splitName = el.attrByName("split")?.asString()
                    isFeatureSplit = el.attrByName("isFeatureSplit")?.asBoolean(false) ?: false
                }
                "uses-sdk" -> {
                    minSdk = el.attr(AndroidAttrs.MIN_SDK_VERSION, "minSdkVersion")?.asInt(1) ?: 1
                    targetSdk = el.attr(AndroidAttrs.TARGET_SDK_VERSION, "targetSdkVersion")?.asInt(minSdk) ?: minSdk
                }
                "uses-permission", "uses-permission-sdk-23" -> {
                    el.attr(AndroidAttrs.NAME, "name")?.asString()?.let(usesPermissions::add)
                }
                "permission" -> {
                    val n = el.attr(AndroidAttrs.NAME, "name")?.asString()
                    if (n != null) {
                        declaredPermissions += DeclaredPermission(
                            n, el.attr(AndroidAttrs.PROTECTION_LEVEL, "protectionLevel")?.asInt(0) ?: 0
                        )
                    }
                }
                "application" -> {
                    appClass = el.attr(AndroidAttrs.NAME, "name")?.asString()
                    appProcess = el.attr(AndroidAttrs.PROCESS, "process")?.asString()
                    appComponentFactory = el.attrByName("appComponentFactory")?.asString()
                    hasCode = el.attr(AndroidAttrs.HAS_CODE, "hasCode")?.asBoolean(true) ?: true
                    extractNativeLibs = el.attrByName("extractNativeLibs")?.asBoolean(true)
                    val labelAttr = el.attr(AndroidAttrs.LABEL, "label")
                    label = labelAttr?.asString()
                    labelResId = labelAttr?.takeIf { it.dataType == BinaryXml.TYPE_REFERENCE }
                        ?.rawData ?: 0
                    iconResId = el.attr(AndroidAttrs.ICON, "icon")?.rawData ?: 0
                    themeResId = el.attr(AndroidAttrs.THEME, "theme")?.rawData ?: 0
                }
                "activity", "activity-alias", "service", "receiver", "provider" -> {
                    flushComponent()
                    cur = ComponentBuilder(kindOf(el.name), el)
                    curDepth = el.depth
                }
                "intent-filter" -> {
                    flushFilter()
                    if (cur != null) {
                        curFilter = FilterBuilder(el.attr(AndroidAttrs.PRIORITY, "priority")?.asInt(0) ?: 0)
                        filterDepth = el.depth
                    }
                }
                "action" -> el.attr(AndroidAttrs.NAME, "name")?.asString()?.let { curFilter?.actions?.add(it) }
                "category" -> el.attr(AndroidAttrs.NAME, "name")?.asString()?.let { curFilter?.categories?.add(it) }
                "data" -> curFilter?.let { f ->
                    el.attr(AndroidAttrs.SCHEME, "scheme")?.asString()?.let(f.schemes::add)
                    el.attr(AndroidAttrs.HOST, "host")?.asString()?.let(f.hosts::add)
                    el.attr(AndroidAttrs.PORT, "port")?.asString()?.let(f.ports::add)
                    el.attr(AndroidAttrs.PATH, "path")?.asString()?.let(f.paths::add)
                    el.attr(AndroidAttrs.PATH_PREFIX, "pathPrefix")?.asString()?.let(f.pathPrefixes::add)
                    el.attr(AndroidAttrs.PATH_PATTERN, "pathPattern")?.asString()?.let(f.pathPatterns::add)
                    el.attr(AndroidAttrs.MIME_TYPE, "mimeType")?.asString()?.let(f.mimeTypes::add)
                }
                "meta-data" -> {
                    val k = el.attr(AndroidAttrs.NAME, "name")?.asString()
                    val v = el.attr(AndroidAttrs.VALUE, "value")?.asString()
                        ?: el.attr(AndroidAttrs.RESOURCE, "resource")?.asString()
                    if (k != null) {
                        if (cur != null) cur!!.metaData[k] = v else appMetaData[k] = v
                    }
                }
                "uses-library", "uses-native-library" -> {
                    el.attr(AndroidAttrs.NAME, "name")?.asString()?.let(nativeLibs::add)
                }
            }
        }
        flushComponent()

        val pkg = packageName
        val fullVersionCode = (versionCodeMajor shl 32) or versionCode
        return ApkManifest(
            packageName = pkg,
            versionCode = fullVersionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = if (targetSdk == 0) minSdk else targetSdk,
            sharedUserId = sharedUserId,
            splitName = splitName,
            isFeatureSplit = isFeatureSplit,
            applicationClassName = appClass?.let { qualify(it, pkg) },
            applicationProcess = appProcess?.let { qualifyProcess(it, pkg) } ?: pkg,
            appComponentFactory = appComponentFactory?.let { qualify(it, pkg) },
            hasCode = hasCode,
            extractNativeLibs = extractNativeLibs,
            label = label,
            labelResId = labelResId,
            iconResId = iconResId,
            themeResId = themeResId,
            usesPermissions = usesPermissions.toList(),
            declaredPermissions = declaredPermissions,
            components = components,
            applicationMetaData = appMetaData,
            usesNativeLibraries = nativeLibs.toList(),
        )
    }

    private fun kindOf(tag: String) = when (tag) {
        "activity" -> ComponentKind.ACTIVITY
        "activity-alias" -> ComponentKind.ACTIVITY_ALIAS
        "service" -> ComponentKind.SERVICE
        "receiver" -> ComponentKind.RECEIVER
        else -> ComponentKind.PROVIDER
    }

    /** `.Foo` -> `pkg.Foo`; `Foo` -> `pkg.Foo`; `a.b.Foo` stays as-is. Mirrors PackageParser. */
    internal fun qualify(name: String, pkg: String): String = when {
        name.startsWith(".") -> pkg + name
        name.contains('.') -> name
        else -> "$pkg.$name"
    }

    /**
     * `:remote` -> `pkg:remote` (a private process); anything else is a global process name
     * and is kept verbatim, matching the platform's own rule.
     */
    internal fun qualifyProcess(name: String, pkg: String): String =
        if (name.startsWith(":")) pkg + name else name

    private class ComponentBuilder(val kind: ComponentKind, el: XmlElement) {
        val name = el.attr(AndroidAttrs.NAME, "name")?.asString().orEmpty()
        val process = el.attr(AndroidAttrs.PROCESS, "process")?.asString()
        // Android's own default for `exported` is "true if the component has an
        // intent-filter", and since API 31 an explicit value is mandatory in that case.
        // We reproduce the historical default so imports of older apps stay faithful.
        val exportedAttr = el.attr(AndroidAttrs.EXPORTED, "exported")
        val enabled = el.attr(AndroidAttrs.ENABLED, "enabled")?.asBoolean(true) ?: true
        val permission = el.attr(AndroidAttrs.PERMISSION, "permission")?.asString()
        val taskAffinity = el.attr(AndroidAttrs.TASK_AFFINITY, "taskAffinity")?.asString()
        val launchMode = el.attr(AndroidAttrs.LAUNCH_MODE, "launchMode")?.asInt(0) ?: 0
        val theme = el.attr(AndroidAttrs.THEME, "theme")?.rawData ?: 0
        val orientation = el.attr(AndroidAttrs.SCREEN_ORIENTATION, "screenOrientation")?.asInt(-1) ?: -1
        val configChanges = el.attr(AndroidAttrs.CONFIG_CHANGES, "configChanges")?.asInt(0) ?: 0
        val foregroundServiceType =
            el.attr(AndroidAttrs.FOREGROUND_SERVICE_TYPE, "foregroundServiceType")?.asInt(0) ?: 0
        val authorities = el.attr(AndroidAttrs.AUTHORITIES, "authorities")?.asString()
            ?.split(';')?.filter { it.isNotBlank() } ?: emptyList()
        val targetActivity = el.attrByName("targetActivity")?.asString()
        val filters = ArrayList<IntentFilterEntry>()
        val metaData = LinkedHashMap<String, String?>()

        fun build(pkg: String) = ComponentEntry(
            kind = kind,
            className = qualify(name, pkg),
            processName = process?.let { qualifyProcess(it, pkg) } ?: pkg,
            exported = exportedAttr?.asBoolean(filters.isNotEmpty()) ?: filters.isNotEmpty(),
            enabled = enabled,
            permission = permission,
            taskAffinity = taskAffinity,
            launchMode = launchMode,
            theme = theme,
            screenOrientation = orientation,
            configChanges = configChanges,
            foregroundServiceType = foregroundServiceType,
            authorities = authorities,
            targetActivity = targetActivity?.let { qualify(it, pkg) },
            intentFilters = filters.toList(),
            metaData = metaData.toMap(),
        )
    }

    private class FilterBuilder(val priority: Int) {
        val actions = ArrayList<String>()
        val categories = ArrayList<String>()
        val schemes = ArrayList<String>()
        val hosts = ArrayList<String>()
        val ports = ArrayList<String>()
        val paths = ArrayList<String>()
        val pathPrefixes = ArrayList<String>()
        val pathPatterns = ArrayList<String>()
        val mimeTypes = ArrayList<String>()

        fun build() = IntentFilterEntry(
            actions, categories, schemes, hosts, ports,
            paths, pathPrefixes, pathPatterns, mimeTypes, priority,
        )
    }
}
