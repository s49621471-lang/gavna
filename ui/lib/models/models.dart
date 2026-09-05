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
    this.needsHostSettings = false,
  });

  final bool ok;
  final String? message;
  final int? vuid;

  /// The user backed out of a picker. Success with nothing done, and never an error:
  /// showing "import failed" to someone who pressed Back is a lie about their own action.
  final bool cancelled;

  /// Android will not show the permission dialog again; only its settings page will do.
  final bool needsHostSettings;

  static EngineOutcome fromMap(Map<Object?, Object?> m) => EngineOutcome(
        ok: (m['ok'] as bool?) ?? false,
        message: (m['message'] as String?) ?? (m['code'] as String?),
        vuid: (m['vuid'] as int?),
        cancelled: (m['cancelled'] as bool?) ?? false,
        needsHostSettings: (m['needsHostSettings'] as bool?) ?? false,
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

  /// Reads a flag that arrives as either a `bool` or the string `"true"`.
  ///
  /// Both shapes are real and neither is a mistake: the engine's `Report.toMap()` is a
  /// `Map<String, String>` because the same map is written into Diagnostics and the device
  /// report, while `bridgesImplemented` is added by the bridge as a genuine `bool`.
  ///
  /// The obvious spelling of "tolerate both" does not work in Dart, and this screen was
  /// dead on a real device because of it:
  ///
  /// ```
  /// Unhandled Exception: type 'String' is not a subtype of type 'bool?' in type cast
  ///   #0 GoogleStatus._flag (package:unique_ui/models/models.dart:298)
  ///   #1 GoogleStatus.fromMap
  ///   #3 _AppDetailsScreenState._load
  /// ```
  ///
  /// `m[key] as bool?` *throws* on a `String` rather than evaluating to null, so the `??`
  /// fallback that was meant to catch it never ran. Every open of App Details threw on the
  /// first field it read. Type-testing with `is` instead of casting is the difference.
  static bool _flag(Map<Object?, Object?> m, String key) {
    final value = m[key];
    if (value is bool) return value;
    return value?.toString().toLowerCase() == 'true';
  }

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

/// One permission group, for one instance, as the engine actually has it.
///
/// `blockedByHost` is the state that matters most and the one a bare switch cannot show:
/// UNIQUE can only ever narrow what it holds itself, so a group the *host* has not been
/// granted cannot be turned on here no matter what the user does. A switch that appears
/// on and does nothing is worse than one that explains itself.
class InstancePermission {
  const InstancePermission({
    required this.group,
    required this.label,
    required this.granted,
    required this.state,
    required this.blockedByHost,
    this.missingHostPermissions = const <String>[],
  });

  final String group;
  final String label;
  final bool granted;
  final String state;

  /// UNIQUE itself does not hold this group, so no instance can be given it *yet*.
  ///
  /// Not a dead end: turning the switch on asks Android for it on UNIQUE's behalf. The
  /// switch used to be disabled here, which left the row explaining a problem the user
  /// had no way to solve.
  final bool blockedByHost;

  /// Exactly which permissions of the group UNIQUE is missing, for the request.
  final List<String> missingHostPermissions;

  static InstancePermission fromMap(Map<Object?, Object?> m) => InstancePermission(
        group: (m['group'] as String?) ?? '',
        label: (m['label'] as String?) ?? '',
        granted: (m['granted'] as bool?) ?? false,
        state: (m['state'] as String?) ?? 'ASK',
        blockedByHost: (m['blockedByHost'] as bool?) ?? false,
        missingHostPermissions: ((m['missingHostPermissions'] as List<Object?>?) ?? const [])
            .map((e) => e.toString())
            .toList(growable: false),
      );
}

/// How one Google flow would be served for one instance, and on what evidence.
///
/// A mode is not a promise that the flow works — no bridge has an implementation yet.
/// It is what UNIQUE *would* do and why, which is the difference between a layer you can
/// reason about and a black box.
class GoogleRoute {
  const GoogleRoute({required this.flow, required this.mode, required this.why});

  final String flow;
  final String mode;
  final String why;

  /// A readable name for the flow, without shouting its enum at the user.
  String get flowLabel => switch (flow) {
        'SIGN_IN' => 'Google Sign-In (legacy)',
        'CREDENTIAL_MANAGER' => 'Sign in with Google',
        'ACCOUNT_MANAGER' => 'Account Manager',
        'FIREBASE_AUTH' => 'Firebase Auth',
        'OAUTH_WEB' => 'OAuth in a browser',
        'FCM' => 'Push messages (FCM)',
        'PLAY_GAMES' => 'Play Games',
        _ => flow,
      };

  String get modeLabel => switch (mode) {
        'VIRTUAL_GMS' => 'In-space Play services',
        'HOST_BRIDGE' => "This device's Play services",
        'PASSTHROUGH' => 'Browser',
        'UNSUPPORTED' => 'Not served',
        _ => mode,
      };

  bool get unsupported => mode == 'UNSUPPORTED';

  static GoogleRoute fromMap(Map<Object?, Object?> m) => GoogleRoute(
        flow: (m['flow'] as String?) ?? '',
        mode: (m['mode'] as String?) ?? 'UNSUPPORTED',
        why: (m['why'] as String?) ?? '',
      );
}

/// One section of the on-device report: what this phone actually is.
class ReportSection {
  const ReportSection({required this.title, required this.values});

  final String title;
  final Map<String, String> values;

  static ReportSection fromMap(Map<Object?, Object?> m) => ReportSection(
        title: (m['title'] as String?) ?? '',
        values: ((m['values'] as Map?) ?? const {})
            .map((k, v) => MapEntry(k.toString(), v?.toString() ?? '-')),
      );
}

/// One step of the physical-device sequence, and what the tester saw.
///
/// A verdict is an observation, not a gate: nothing in the compatibility matrix moves
/// because this says so. It travels with the diagnostics package so it can be read
/// alongside the machine's own record rather than instead of it.
enum StepVerdict { notRun, pass, fail, blocked, skipped }

class ChecklistStep {
  const ChecklistStep({
    required this.id,
    required this.title,
    required this.what,
    required this.verdict,
    required this.note,
  });

  final String id;
  final String title;
  final String what;
  final StepVerdict verdict;
  final String note;

  bool get done => verdict != StepVerdict.notRun;

  static StepVerdict _verdict(String? raw) => switch (raw) {
        'PASS' => StepVerdict.pass,
        'FAIL' => StepVerdict.fail,
        'BLOCKED' => StepVerdict.blocked,
        'SKIPPED' => StepVerdict.skipped,
        _ => StepVerdict.notRun,
      };

  static String encode(StepVerdict v) => switch (v) {
        StepVerdict.pass => 'PASS',
        StepVerdict.fail => 'FAIL',
        StepVerdict.blocked => 'BLOCKED',
        StepVerdict.skipped => 'SKIPPED',
        StepVerdict.notRun => 'NOT_RUN',
      };

  static ChecklistStep fromMap(Map<Object?, Object?> m) => ChecklistStep(
        id: (m['id'] as String?) ?? '',
        title: (m['title'] as String?) ?? '',
        what: (m['what'] as String?) ?? '',
        verdict: _verdict(m['verdict'] as String?),
        note: (m['note'] as String?) ?? '',
      );
}
