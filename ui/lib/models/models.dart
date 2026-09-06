import 'dart:typed_data';

import '../l10n/strings.dart';

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

  /// The translation key for why this app cannot be added, or null when it can.
  ///
  /// A key rather than a sentence: this is read straight into a list tile, and a hardcoded
  /// English string here is one the Russian interface could never replace.
  String? get blockedKey => hasArm64 ? null : 'add.blocked.noArm64';

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

/// The result of an engine action. Failures carry both a code and a message.
class EngineOutcome {
  const EngineOutcome({
    required this.ok,
    this.code,
    this.message,
    this.vuid,
    this.cancelled = false,
    this.needsHostSettings = false,
  });

  final bool ok;

  /// Why it failed, as a stable identifier the interface translates — see [describe].
  final String? code;

  /// The engine's own English prose. Carries the specifics a code cannot: which library,
  /// which page size, which exception. Shown when there is no translation for [code].
  final String? message;

  final int? vuid;

  /// The user backed out of a picker. Success with nothing done, and never an error:
  /// showing "import failed" to someone who pressed Back is a lie about their own action.
  final bool cancelled;

  /// Android will not show the permission dialog again; only its settings page will do.
  final bool needsHostSettings;

  /// What to show the user: the translation of [code] when there is one, then the
  /// engine's own sentence, then [fallback].
  ///
  /// In that order because each step loses something. A translated code is the only text
  /// a Russian reader can act on; the English message is at least specific; [fallback] is
  /// generic but never absent. [args] fills placeholders the translation declares — the
  /// permission-group name above all, which the caller knows and the engine spells in
  /// English.
  String describe(Strings s, String fallback, [Map<String, Object?>? args]) {
    final key = code;
    if (key != null) {
      final translated = s.orElse('engine.$key', '', args);
      if (translated.isNotEmpty) return translated;
    }
    return message ?? fallback;
  }

  static EngineOutcome fromMap(Map<Object?, Object?> m) => EngineOutcome(
        ok: (m['ok'] as bool?) ?? false,
        code: (m['code'] as String?),
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
    required this.profileOrdinal,
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
  /// What the engine stored. English when the engine named it — see [profileOrdinal].
  final String profileName;

  /// The number in an automatic profile name, or null when a person named it themselves.
  /// Present so the interface can say "Профиль 2" instead of the stored "Profile 2".
  final int? profileOrdinal;

  final String androidId;
  final String instanceId;
  final int generation;
  final int dataBytes;
  final int cacheBytes;
  final int externalBytes;
  final Uint8List? icon;
  final bool running;

  int get totalBytes => dataBytes + cacheBytes + externalBytes;

  /// What to call this profile on screen: the automatic name in the reader's own
  /// language, or exactly what a person typed when they named it themselves.
  String profileLabel(Strings s) => profileOrdinal == null
      ? profileName
      : s.t('profile.name', {'n': profileOrdinal});

  VirtualApp copyWith({Uint8List? icon, bool? running}) => VirtualApp(
        vuid: vuid,
        packageName: packageName,
        versionCode: versionCode,
        label: label,
        profileName: profileName,
        profileOrdinal: profileOrdinal,
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
        profileOrdinal: (m['profileOrdinal'] as int?),
        androidId: (m['androidId'] as String?) ?? '',
        instanceId: (m['instanceId'] as String?) ?? '',
        generation: (m['generation'] as int?) ?? 1,
        dataBytes: (m['dataBytes'] as int?) ?? 0,
        cacheBytes: (m['cacheBytes'] as int?) ?? 0,
        externalBytes: (m['externalBytes'] as int?) ?? 0,
      );
}

/// One access the user grants on a Settings screen rather than in a dialog.
///
/// Held by UNIQUE, not by a copy: the uid that Android checks is UNIQUE's, so turning one
/// on turns it on for every app inside. The screen said so before it offered the button,
/// because a per-app switch that is secretly global is the kind of thing people only find
/// out about afterwards.
class SpecialAccess {
  const SpecialAccess({required this.id, required this.granted});

  final String id;
  final bool granted;

  static SpecialAccess fromMap(Map<Object?, Object?> m) => SpecialAccess(
        id: (m['id'] as String?) ?? '',
        granted: (m['granted'] as bool?) ?? false,
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
  /// `Map<String, String>` because the same map goes into the structured log, while
  /// `bridgesImplemented` is added by the bridge as a genuine `bool`.
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
