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

/// One virtual instance shown on Home.
class VirtualApp {
  const VirtualApp({
    required this.vuid,
    required this.packageName,
    required this.label,
    required this.profileName,
    required this.versionName,
    required this.androidId,
    required this.dataBytes,
    required this.cacheBytes,
    this.icon,
    this.running = false,
  });

  final int vuid;
  final String packageName;
  final String label;
  final String profileName;
  final String versionName;
  final String androidId;
  final int dataBytes;
  final int cacheBytes;
  final Uint8List? icon;
  final bool running;

  VirtualApp copyWith({Uint8List? icon, bool? running}) => VirtualApp(
        vuid: vuid,
        packageName: packageName,
        label: label,
        profileName: profileName,
        versionName: versionName,
        androidId: androidId,
        dataBytes: dataBytes,
        cacheBytes: cacheBytes,
        icon: icon ?? this.icon,
        running: running ?? this.running,
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
