import 'package:flutter/services.dart';

import '../models/models.dart';

/// Client for the Kotlin side.
///
/// A `MethodChannel` for request/response, matching `UniqueBridge` on the Android side.
/// Every call returns real data or
/// throws; nothing here invents a value when the platform does not answer, because a
/// fabricated engine status is worse than an error - it would make a broken build look
/// healthy.
class UniqueBridgeClient {
  UniqueBridgeClient._();

  static final instance = UniqueBridgeClient._();

  static const _method = MethodChannel('com.unique/bridge');

  Future<EngineStatus> engineStatus() async {
    final result = await _method.invokeMapMethod<Object?, Object?>('engineStatus');
    return EngineStatus.fromMap(result ?? const {});
  }

  Future<List<InstalledApp>> listInstalledApps({bool includeSystem = false}) async {
    final result = await _method.invokeListMethod<Object?>(
      'listInstalledApps',
      {'includeSystem': includeSystem},
    );
    return (result ?? const [])
        .map((e) => InstalledApp.fromMap(e as Map<Object?, Object?>))
        .toList();
  }

  Future<Uint8List?> appIcon(String packageName) async {
    return _method.invokeMethod<Uint8List>('appIcon', {'package': packageName});
  }

  Future<List<VirtualApp>> listInstances() async {
    final result = await _method.invokeListMethod<Object?>('listInstances');
    return (result ?? const [])
        .map((e) => VirtualApp.fromMap(e as Map<Object?, Object?>))
        .toList();
  }

  Future<EngineOutcome> importInstalled(String packageName) async =>
      _outcome('importInstalled', {'package': packageName});

  Future<EngineOutcome> importApk(List<String> paths) async =>
      _outcome('importApk', {'paths': paths});

  /// Opens the system file picker and imports whatever the user chose.
  ///
  /// Returns an outcome with `cancelled` set when the user backed out, which is not a
  /// failure and must not be shown as one.
  Future<EngineOutcome> importApkFromPicker() async =>
      _outcome('importApkFromPicker', const {});

  Future<EngineOutcome> cloneInstance(String packageName) async =>
      _outcome('cloneInstance', {'package': packageName});

  Future<EngineOutcome> launchInstance(int vuid) async =>
      _outcome('launchInstance', {'vuid': vuid});

  /// Whether an instance's expansion files are in place. Empty when the engine cannot say.
  Future<Map<String, String>> guestAssetStatus(int vuid) async {
    final result = await _method
        .invokeMapMethod<Object?, Object?>('guestAssetStatus', {'vuid': vuid});
    return {
      for (final entry in (result ?? const {}).entries)
        '${entry.key}': '${entry.value}',
    };
  }

  Future<EngineOutcome> removeInstance(int vuid) async =>
      _outcome('removeInstance', {'vuid': vuid});

  Future<EngineOutcome> clearCache(int vuid) async =>
      _outcome('clearCache', {'vuid': vuid});

  Future<EngineOutcome> clearData(int vuid) async =>
      _outcome('clearData', {'vuid': vuid});

  Future<EngineOutcome> _outcome(String method, Map<String, Object?> args) async {
    final result = await _method.invokeMapMethod<Object?, Object?>(method, args);
    return EngineOutcome.fromMap(result ?? const {});
  }

  /// The Settings-screen accesses UNIQUE holds, and whether each is on.
  Future<List<SpecialAccess>> specialAccess() async {
    final result = await _method.invokeListMethod<Object?>('specialAccess');
    return (result ?? const [])
        .map((e) => SpecialAccess.fromMap(e as Map<Object?, Object?>))
        .toList();
  }

  Future<EngineOutcome> openSpecialAccess(String id) async {
    final result = await _method
        .invokeMapMethod<Object?, Object?>('openSpecialAccess', {'id': id});
    return EngineOutcome.fromMap(result ?? const {});
  }

  /// How each Google flow would be served for one instance, and why.
  Future<List<GoogleRoute>> googleRouting(int vuid) async {
    final result =
        await _method.invokeListMethod<Object?>('googleRouting', {'vuid': vuid});
    return (result ?? const [])
        .map((e) => GoogleRoute.fromMap(e as Map<Object?, Object?>))
        .toList();
  }

  /// The permission groups this instance's app actually asks for, and their state.
  Future<List<InstancePermission>> instancePermissions(int vuid) async {
    final result = await _method.invokeListMethod<Object?>(
      'instancePermissions',
      {'vuid': vuid},
    );
    return (result ?? const [])
        .map((e) => InstancePermission.fromMap(e as Map<Object?, Object?>))
        .toList();
  }

  Future<EngineOutcome> setInstancePermission(
    int vuid,
    String group,
    bool granted,
  ) async =>
      _outcome('setInstancePermission',
          {'vuid': vuid, 'group': group, 'granted': granted});

  /// Opens UNIQUE's own page in Android settings.
  ///
  /// The one route left when the platform has stopped offering a permission dialog:
  /// telling the user to "grant it in settings" without taking them there is the kind of
  /// instruction people give up on.
  Future<EngineOutcome> openHostSettings() async => _outcome('openHostSettings', {});

  /// What this device can offer a virtualized app's Google flows.
  Future<GoogleStatus> googleStatus() async {
    final result = await _method.invokeMapMethod<Object?, Object?>('googleStatus');
    return GoogleStatus.fromMap(result ?? const {});
  }

  /// Interface preferences, which persist on the engine side rather than in memory.
  Future<Map<String, Object?>> uiSettings() async =>
      (await _method.invokeMapMethod<Object?, Object?>('uiSettings'))
          ?.map((k, v) => MapEntry(k.toString(), v)) ??
      const {};

  Future<void> setUiSetting(String key, Object value) =>
      _method.invokeMethod<Object?>('setUiSetting', {'key': key, 'value': value});
}
