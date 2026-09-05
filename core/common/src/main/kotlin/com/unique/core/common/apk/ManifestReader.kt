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
        var networkSecurityConfigResId = 0
        var usesCleartextTraffic: Boolean? = null
        var labelResId = 0
        var iconResId = 0
        var roundIconResId = 0
        var bannerResId = 0
        var logoResId = 0
        var themeResId = 0
        // Unstated until <application> is seen; resolved against targetSdk below, which is
        // the platform's own rule and the reason every activity could inherit it.
        var appHardwareAccelerated: Boolean? = null
        var largeHeap = false
        var supportsRtl = false
        var requestLegacyExternalStorage = false
        var appDirectBootAware = false

        val usesPermissions = LinkedHashSet<String>()
        val declaredPermissions = ArrayList<DeclaredPermission>()
        val components = ArrayList<ComponentEntry>()
        val appMetaData = LinkedHashMap<String, String?>()
        val appMetaDataEntries = ArrayList<MetaDataEntry>()
        val nativeLibs = LinkedHashSet<String>()

        // Mutable state for the component currently being assembled.
        var cur: ComponentBuilder? = null
        var curFilter: FilterBuilder? = null
        // Depth at which the current component / filter started, so we know when it ends.
        var curDepth = -1
        var filterDepth = -1

        // targetSdk is read from <uses-sdk>, which the tooling always emits before
        // <application>; the fallback keeps a hand-written manifest that omits it from
        // silently turning hardware acceleration off for every activity.
        fun effectiveTargetSdk(): Int = if (targetSdk != 0) targetSdk else maxOf(minSdk, 14)

        fun flushFilter() {
            val f = curFilter ?: return
            cur?.filters?.add(f.build())
            curFilter = null
            filterDepth = -1
        }

        fun flushComponent() {
            flushFilter()
            val c = cur ?: return
            // A component with no `android:name` is not a component. Real APKs contain
            // them — a `<provider>` left behind by a manifest merge, its name stripped by a
            // build tool — and qualifying an empty name against the package produces
            // `com.example.app.`, which the class loader answers with
            //
            //   ClassNotFoundException: Invalid name: com.openai.chatgpt.
            //
            // once per publish attempt, forever. Dropping it here is what the platform
            // does too, and it keeps the noise out of every layer downstream.
            if (c.name.isBlank()) {
                cur = null
                curDepth = -1
                return
            }
            components += c.build(packageName, appHardwareAccelerated ?: (effectiveTargetSdk() >= 14))
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
                    versionCodeMajor =
                        (el.attr(AndroidAttrs.VERSION_CODE_MAJOR, "versionCodeMajor")?.asInt(0) ?: 0).toLong()
                    versionName = el.attr(AndroidAttrs.VERSION_NAME, "versionName")?.asString()
                    sharedUserId = el.attr(AndroidAttrs.SHARED_USER_ID, "sharedUserId")?.asString()
                    splitName = el.attrByName("split")?.asString()
                    isFeatureSplit =
                        el.attr(AndroidAttrs.IS_FEATURE_SPLIT, "isFeatureSplit")?.asBoolean(false) ?: false
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
                    appComponentFactory =
                        el.attr(AndroidAttrs.APP_COMPONENT_FACTORY, "appComponentFactory")?.asString()
                    hasCode = el.attr(AndroidAttrs.HAS_CODE, "hasCode")?.asBoolean(true) ?: true
                    extractNativeLibs =
                        el.attr(AndroidAttrs.EXTRACT_NATIVE_LIBS, "extractNativeLibs")?.asBoolean(true)
                    val labelAttr = el.attr(AndroidAttrs.LABEL, "label")
                    label = labelAttr?.asString()
                    labelResId = labelAttr?.takeIf { it.dataType == BinaryXml.TYPE_REFERENCE }
                        ?.rawData ?: 0
                    iconResId = el.attr(AndroidAttrs.ICON, "icon")?.rawData ?: 0
                    roundIconResId = el.attr(AndroidAttrs.ROUND_ICON, "roundIcon")?.rawData ?: 0
                    bannerResId = el.attr(AndroidAttrs.BANNER, "banner")?.rawData ?: 0
                    logoResId = el.attr(AndroidAttrs.LOGO, "logo")?.rawData ?: 0
                    networkSecurityConfigResId =
                        el.attr(AndroidAttrs.NETWORK_SECURITY_CONFIG, "networkSecurityConfig")
                            ?.rawData ?: 0
                    usesCleartextTraffic =
                        el.attr(AndroidAttrs.USES_CLEARTEXT_TRAFFIC, "usesCleartextTraffic")
                            ?.asBoolean(true)
                    themeResId = el.attr(AndroidAttrs.THEME, "theme")?.rawData ?: 0
                    appHardwareAccelerated =
                        el.attr(AndroidAttrs.HARDWARE_ACCELERATED, "hardwareAccelerated")
                            ?.asBoolean(true)
                    largeHeap = el.attr(AndroidAttrs.LARGE_HEAP, "largeHeap")?.asBoolean(false) ?: false
                    supportsRtl = el.attr(AndroidAttrs.SUPPORTS_RTL, "supportsRtl")?.asBoolean(false) ?: false
                    requestLegacyExternalStorage = el.attr(
                        AndroidAttrs.REQUEST_LEGACY_EXTERNAL_STORAGE, "requestLegacyExternalStorage",
                    )?.asBoolean(false) ?: false
                    appDirectBootAware =
                        el.attr(AndroidAttrs.DIRECT_BOOT_AWARE, "directBootAware")?.asBoolean(false) ?: false
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
                    val valueAttr = el.attr(AndroidAttrs.VALUE, "value")
                    val resourceAttr = el.attr(AndroidAttrs.RESOURCE, "resource")
                    val v = valueAttr?.asString() ?: resourceAttr?.asString()
                    if (k != null) {
                        val typed = MetaDataEntry(
                            name = k,
                            resourceId = resourceAttr?.rawData ?: 0,
                            valueType = valueAttr?.dataType ?: BinaryXml.TYPE_NULL,
                            valueData = valueAttr?.rawData ?: 0,
                            valueString = valueAttr?.takeIf { it.dataType == BinaryXml.TYPE_STRING }
                                ?.stringValue,
                        )
                        val component = cur
                        if (component != null) {
                            component.metaData[k] = v
                            component.metaDataEntries += typed
                        } else {
                            appMetaData[k] = v
                            appMetaDataEntries += typed
                        }
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
            networkSecurityConfigResId = networkSecurityConfigResId,
            usesCleartextTraffic = usesCleartextTraffic,
            themeResId = themeResId,
            usesPermissions = usesPermissions.toList(),
            declaredPermissions = declaredPermissions,
            components = components,
            applicationMetaData = appMetaData,
            usesNativeLibraries = nativeLibs.toList(),
            applicationMetaDataEntries = appMetaDataEntries,
            hardwareAccelerated = appHardwareAccelerated
                ?: ((if (targetSdk == 0) minSdk else targetSdk) >= 14),
            largeHeap = largeHeap,
            supportsRtl = supportsRtl,
            requestLegacyExternalStorage = requestLegacyExternalStorage,
            directBootAware = appDirectBootAware,
            roundIconResId = roundIconResId,
            bannerResId = bannerResId,
            logoResId = logoResId,
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

    private class ComponentBuilder(val kind: ComponentKind, private val el: XmlElement) {
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
        val readPermission = el.attr(AndroidAttrs.READ_PERMISSION, "readPermission")?.asString()
        val writePermission = el.attr(AndroidAttrs.WRITE_PERMISSION, "writePermission")?.asString()
        val grantUriPermissions =
            el.attr(AndroidAttrs.GRANT_URI_PERMISSIONS, "grantUriPermissions")?.asBoolean(false) ?: false
        val targetActivity = el.attr(AndroidAttrs.TARGET_ACTIVITY, "targetActivity")?.asString()
        val filters = ArrayList<IntentFilterEntry>()
        val metaData = LinkedHashMap<String, String?>()
        val metaDataEntries = ArrayList<MetaDataEntry>()

        private val labelAttr = el.attr(AndroidAttrs.LABEL, "label")
        val labelResId = labelAttr?.takeIf { it.dataType == BinaryXml.TYPE_REFERENCE }?.rawData ?: 0
        val labelText = labelAttr?.takeIf { it.dataType == BinaryXml.TYPE_STRING }?.stringValue
        val iconResId = el.attr(AndroidAttrs.ICON, "icon")?.rawData ?: 0

        /**
         * The window and task attributes, read whether or not this is an activity.
         *
         * Reading them unconditionally costs one pass over an attribute list that is
         * already in memory, and it means a `<service>` that grows a window attribute in
         * some future release does not need this code changed to notice.
         */
        fun window(hardwareAcceleratedDefault: Boolean) = WindowAttributes(
            hardwareAccelerated = el.attr(AndroidAttrs.HARDWARE_ACCELERATED, "hardwareAccelerated")
                ?.asBoolean(hardwareAcceleratedDefault) ?: hardwareAcceleratedDefault,
            softInputMode = el.attr(AndroidAttrs.WINDOW_SOFT_INPUT_MODE, "windowSoftInputMode")
                ?.asInt(0) ?: 0,
            uiOptions = el.attr(AndroidAttrs.UI_OPTIONS, "uiOptions")?.asInt(0) ?: 0,
            documentLaunchMode = el.attr(AndroidAttrs.DOCUMENT_LAUNCH_MODE, "documentLaunchMode")
                ?.asInt(0) ?: 0,
            maxRecents = el.attr(AndroidAttrs.MAX_RECENTS, "maxRecents")?.asInt(-1) ?: -1,
            colorMode = el.attr(AndroidAttrs.COLOR_MODE, "colorMode")?.asInt(0) ?: 0,
            rotationAnimation = el.attr(AndroidAttrs.ROTATION_ANIMATION, "rotationAnimation")
                ?.asInt(-1) ?: -1,
            lockTaskMode = el.attr(AndroidAttrs.LOCK_TASK_MODE, "lockTaskMode")?.asInt(0) ?: 0,
            persistableMode = el.attr(AndroidAttrs.PERSISTABLE_MODE, "persistableMode")?.asInt(-1) ?: -1,
            resizeable = el.attr(AndroidAttrs.RESIZEABLE_ACTIVITY, "resizeableActivity")?.asBoolean(true),
            supportsPictureInPicture = el.attr(
                AndroidAttrs.SUPPORTS_PICTURE_IN_PICTURE, "supportsPictureInPicture",
            )?.asBoolean(false) ?: false,
            maxAspectRatio = floatAttr(el.attr(AndroidAttrs.MAX_ASPECT_RATIO, "maxAspectRatio")),
            minAspectRatio = floatAttr(el.attr(AndroidAttrs.MIN_ASPECT_RATIO, "minAspectRatio")),
            excludeFromRecents = flag(AndroidAttrs.EXCLUDE_FROM_RECENTS, "excludeFromRecents"),
            allowTaskReparenting = flag(AndroidAttrs.ALLOW_TASK_REPARENTING, "allowTaskReparenting"),
            finishOnTaskLaunch = flag(AndroidAttrs.FINISH_ON_TASK_LAUNCH, "finishOnTaskLaunch"),
            clearTaskOnLaunch = flag(AndroidAttrs.CLEAR_TASK_ON_LAUNCH, "clearTaskOnLaunch"),
            alwaysRetainTaskState = flag(AndroidAttrs.ALWAYS_RETAIN_TASK_STATE, "alwaysRetainTaskState"),
            stateNotNeeded = flag(AndroidAttrs.STATE_NOT_NEEDED, "stateNotNeeded"),
            noHistory = flag(AndroidAttrs.NO_HISTORY, "noHistory"),
            multiprocess = flag(AndroidAttrs.MULTIPROCESS, "multiprocess"),
            immersive = flag(AndroidAttrs.IMMERSIVE, "immersive"),
            showForAllUsers = flag(AndroidAttrs.SHOW_FOR_ALL_USERS, "showForAllUsers"),
            autoRemoveFromRecents = flag(AndroidAttrs.AUTO_REMOVE_FROM_RECENTS, "autoRemoveFromRecents"),
            relinquishTaskIdentity = flag(AndroidAttrs.RELINQUISH_TASK_IDENTITY, "relinquishTaskIdentity"),
            resumeWhilePausing = flag(AndroidAttrs.RESUME_WHILE_PAUSING, "resumeWhilePausing"),
            showWhenLocked = flag(AndroidAttrs.SHOW_WHEN_LOCKED, "showWhenLocked"),
            turnScreenOn = flag(AndroidAttrs.TURN_SCREEN_ON, "turnScreenOn"),
            directBootAware = flag(AndroidAttrs.DIRECT_BOOT_AWARE, "directBootAware"),
        )

        private fun flag(id: Int, name: String): Boolean =
            el.attr(id, name)?.asBoolean(false) ?: false

        /** `android:maxAspectRatio` is a float; anything else means "unstated". */
        private fun floatAttr(attr: XmlAttribute?): Float = when (attr?.dataType) {
            null -> 0f
            BinaryXml.TYPE_FLOAT -> java.lang.Float.intBitsToFloat(attr.rawData)
            BinaryXml.TYPE_STRING -> attr.stringValue?.trim()?.toFloatOrNull() ?: 0f
            BinaryXml.TYPE_NULL, BinaryXml.TYPE_REFERENCE -> 0f
            else -> attr.rawData.toFloat()
        }

        fun build(pkg: String, hardwareAcceleratedDefault: Boolean) = ComponentEntry(
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
            readPermission = readPermission,
            writePermission = writePermission,
            grantUriPermissions = grantUriPermissions,
            targetActivity = targetActivity?.let { qualify(it, pkg) },
            intentFilters = filters.toList(),
            metaData = metaData.toMap(),
            metaDataEntries = metaDataEntries.toList(),
            window = window(hardwareAcceleratedDefault),
            labelResId = labelResId,
            iconResId = iconResId,
            labelText = labelText,
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
