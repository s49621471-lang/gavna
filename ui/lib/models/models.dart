import 'dart:typed_data';

/// What the engine can do on this device, as reported by the native side.
///
/// The `*Implemented` flags exist so the interface never offers an action that silently
/// does nothing: a build where virtual launch is not wired up says so, in the UI, rather
/// than showing a Launch button that fails.
class EngineStatus {
  const EngineStatus({
    required this.versionName,
    required this.sdkInt,
    required this.abis,
    required this.is64BitOnly,
    required this.nativeLoaded,
    required this.nativeLoadError,
    required this.pageSizeBytes,
    required this.hiddenApiGranted,
    required this.hiddenApiDetail,
    required this.virtualLaunchImplemented,
    required this.ioRedirectImplemented,
    required this.settingsInterceptionImplemented,
  });

  final String versionName;
  final int sdkInt;
  final List<String> abis;
  final bool is64BitOnly;
  final bool nativeLoaded;
  final String? nativeLoadError;
  final int pageSizeBytes;
  final bool hiddenApiGranted;
  final String? hiddenApiDetail;
  final bool virtualLaunchImplemented;
  final bool ioRedirectImplemented;
  final bool settingsInterceptionImplemented;

  bool get supportsArm64 => abis.contains('arm64-v8a');
  bool get usesLargePages => pageSizeBytes > 4096;

  /// True when the engine could actually run something. Drives the home banner.
  bool get ready => nativeLoaded && hiddenApiGranted && virtualLaunchImplemented;

  static EngineStatus fromMap(Map<Object?, Object?> m) => EngineStatus(
        versionName: (m['versionName'] as String?) ?? '-',
        sdkInt: (m['sdkInt'] as int?) ?? 0,
        abis: ((m['abis'] as List?) ?? const []).map((e) => e as String).toList(),
        is64BitOnly: (m['is64BitOnly'] as bool?) ?? false,
        nativeLoaded: (m['nativeLoaded'] as bool?) ?? false,
        nativeLoadError: m['nativeLoadError'] as String?,
        pageSizeBytes: (m['pageSizeBytes'] as int?) ?? 4096,
        hiddenApiGranted: (m['hiddenApiGranted'] as bool?) ?? false,
        hiddenApiDetail: m['hiddenApiDetail'] as String?,
        virtualLaunchImplemented: (m['virtualLaunchImplemented'] as bool?) ?? false,
        ioRedirectImplemented: (m['ioRedirectImplemented'] as bool?) ?? false,
        settingsInterceptionImplemented:
            (m['settingsInterceptionImplemented'] as bool?) ?? false,
      );
}

/// An application installed on the device, offered in Add App.
class InstalledApp {
  const InstalledApp({
    required this.packageName,
    required this.label,
    required this.isSystem,
    required this.minSdk,
    required this.targetSdk,
    required this.splitCount,
    required this.hasArm64,
  });

  final String packageName;
  final String label;
  final bool isSystem;
  final int minSdk;
  final int targetSdk;
  final int splitCount;
  final bool hasArm64;

  /// Why this app cannot be added, or null when it can.
  String? get blockedReason =>
      hasArm64 ? null : 'No 64-bit ARM code. UNIQUE runs arm64-v8a only.';

  static InstalledApp fromMap(Map<Object?, Object?> m) => InstalledApp(
        packageName: m['package'] as String,
        label: (m['label'] as String?) ?? (m['package'] as String),
        isSystem: (m['system'] as bool?) ?? false,
        minSdk: (m['minSdk'] as int?) ?? 0,
        targetSdk: (m['targetSdk'] as int?) ?? 0,
        splitCount: (m['splitCount'] as int?) ?? 0,
        hasArm64: (m['hasArm64'] as bool?) ?? true,
      );
}

/// The result of an engine action. Failures carry a message meant for the user.
class EngineOutcome {
  const EngineOutcome({
    required this.ok,
    this.message,
    this.vuid,
    this.cancelled = false,
  });

  final bool ok;
  final String? message;
  final int? vuid;

  /// The user backed out of a picker. Success with nothing done, and never an error:
  /// showing "import failed" to someone who pressed Back is a lie about their own action.
  final bool cancelled;

  static EngineOutcome fromMap(Map<Object?, Object?> m) => EngineOutcome(
        ok: (m['ok'] as bool?) ?? false,
        message: (m['message'] as String?) ?? (m['code'] as String?),
        vuid: (m['vuid'] as int?),
        cancelled: (m['cancelled'] as bool?) ?? false,
      );
}

/// One virtual instance shown on Home.
class VirtualApp {
  const VirtualApp({
    required this.vuid,
    required this.packageName,
    required this.versionCode,
    required this.label,
    required this.profileName,
    required this.androidId,
    required this.instanceId,
    required this.generation,
    required this.dataBytes,
    required this.cacheBytes,
    required this.externalBytes,
    this.icon,
    this.running = false,
  });

  final int vuid;
  final String packageName;
  final int versionCode;
  final String label;
  final String profileName;
  final String androidId;
  final String instanceId;
  final int generation;
  final int dataBytes;
  final int cacheBytes;
  final int externalBytes;
  final Uint8List? icon;
  final bool running;

  int get totalBytes => dataBytes + cacheBytes + externalBytes;

  VirtualApp copyWith({Uint8List? icon, bool? running}) => VirtualApp(
        vuid: vuid,
        packageName: packageName,
        versionCode: versionCode,
        label: label,
        profileName: profileName,
        androidId: androidId,
        instanceId: instanceId,
        generation: generation,
        dataBytes: dataBytes,
        cacheBytes: cacheBytes,
        externalBytes: externalBytes,
        icon: icon ?? this.icon,
        running: running ?? this.running,
      );

  static VirtualApp fromMap(Map<Object?, Object?> m) => VirtualApp(
        vuid: (m['vuid'] as int?) ?? -1,
        packageName: (m['package'] as String?) ?? '',
        versionCode: (m['versionCode'] as int?) ?? 0,
        label: (m['label'] as String?) ?? (m['package'] as String? ?? ''),
        profileName: (m['profileName'] as String?) ?? 'Profile 1',
        androidId: (m['androidId'] as String?) ?? '',
        instanceId: (m['instanceId'] as String?) ?? '',
        generation: (m['generation'] as int?) ?? 1,
        dataBytes: (m['dataBytes'] as int?) ?? 0,
        cacheBytes: (m['cacheBytes'] as int?) ?? 0,
        externalBytes: (m['externalBytes'] as int?) ?? 0,
      );
}

enum DiagLevel { debug, info, warn, error }

class DiagRecord {
  const DiagRecord({
    required this.timestamp,
    required this.channel,
    required this.level,
    required this.code,
    required this.fields,
    this.packageName,
    this.vuid,
  });

  final DateTime timestamp;
  final String channel;
  final DiagLevel level;
  final String code;
  final Map<String, String> fields;
  final String? packageName;
  final int? vuid;

  static DiagRecord fromMap(Map<Object?, Object?> m) => DiagRecord(
        timestamp:
            DateTime.fromMillisecondsSinceEpoch((m['timestamp'] as int?) ?? 0),
        channel: (m['channel'] as String?) ?? 'LAUNCH',
        level: DiagLevel.values.firstWhere(
          (l) => l.name.toUpperCase() == ((m['level'] as String?) ?? 'INFO'),
          orElse: () => DiagLevel.info,
        ),
        code: (m['code'] as String?) ?? '',
        fields: ((m['fields'] as Map?) ?? const {})
            .map((k, v) => MapEntry(k.toString(), v.toString())),
        packageName: m['package'] as String?,
        vuid: m['vuid'] as int?,
      );
}

/// Where a diagnostics package landed, and what went into it.
///
/// Carries the counts as well as the path because "exported" on its own does not tell
/// the user whether the export is worth sending: a package assembled while no virtual
/// process was alive contains far less than one taken with the app still running, and
/// that distinction is the difference between a useful report and a wasted round trip.
class DiagnosticsExportResult {
  const DiagnosticsExportResult({
    required this.ok,
    this.path,
    this.name,
    this.bytes = 0,
    this.processes = 0,
    this.lines = 0,
    this.message,
  });

  final bool ok;
  final String? path;
  final String? name;
  final int bytes;
  final int processes;
  final int lines;
  final String? message;

  static DiagnosticsExportResult fromMap(Map<Object?, Object?> m) =>
      DiagnosticsExportResult(
        ok: (m['ok'] as bool?) ?? false,
        path: m['path'] as String?,
        name: m['name'] as String?,
        bytes: (m['bytes'] as num?)?.toInt() ?? 0,
        processes: (m['processes'] as num?)?.toInt() ?? 0,
        lines: (m['lines'] as num?)?.toInt() ?? 0,
        message: m['message'] as String?,
      );
}

/// What the device can offer a virtualized app's Google flows.
///
/// Every field is read from the device when the screen opens. `bridgesImplemented` is
/// carried explicitly rather than inferred: a screen that shows a healthy device and says
/// nothing about the bridges having no bodies would be telling two thirds of the truth.
class GoogleStatus {
  const GoogleStatus({
    required this.gmsPresent,
    required this.gmsEnabled,
    required this.gmsVersionCode,
    required this.gmsVersionName,
    required this.vendingPresent,
    required this.gsfPresent,
    required this.customTabsPackage,
    required this.hostGmsAvailable,
    required this.virtualGmsInstalled,
    required this.customTabsAvailable,
    required this.bridgesImplemented,
    required this.note,
  });

  final bool gmsPresent;
  final bool gmsEnabled;
  final String gmsVersionCode;
  final String gmsVersionName;
  final bool vendingPresent;
  final bool gsfPresent;
  final String customTabsPackage;
  final bool hostGmsAvailable;
  final bool virtualGmsInstalled;
  final bool customTabsAvailable;
  final bool bridgesImplemented;
  final String note;

  /// Present but not usable: installed, or installed and disabled, or a version too old
  /// to answer. The state that produces the most confusing app-side failures.
  bool get presentButUnusable => gmsPresent && !hostGmsAvailable;

  static bool _flag(Map<Object?, Object?> m, String key) =>
      (m[key] as bool?) ?? m[key]?.toString() == 'true';

  static String _text(Map<Object?, Object?> m, String key) =>
      m[key]?.toString() ?? '-';

  static GoogleStatus fromMap(Map<Object?, Object?> m) => GoogleStatus(
        gmsPresent: _flag(m, 'gmsPresent'),
        gmsEnabled: _flag(m, 'gmsEnabled'),
        gmsVersionCode: _text(m, 'gmsVersionCode'),
        gmsVersionName: _text(m, 'gmsVersionName'),
        vendingPresent: _flag(m, 'vendingPresent'),
        gsfPresent: _flag(m, 'gsfPresent'),
        customTabsPackage: _text(m, 'customTabs'),
        hostGmsAvailable: _flag(m, 'hostGmsAvailable'),
        virtualGmsInstalled: _flag(m, 'virtualGmsInstalled'),
        customTabsAvailable: _flag(m, 'customTabsAvailable'),
        bridgesImplemented: _flag(m, 'bridgesImplemented'),
        note: _text(m, 'note'),
      );
}
