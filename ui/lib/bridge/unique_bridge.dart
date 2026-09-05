import 'package:flutter/services.dart';

import '../models/models.dart';

/// Client for the Kotlin side.
///
/// A `MethodChannel` for request/response and an `EventChannel` for the diagnostics
/// stream, matching `UniqueBridge` on the Android side. Every call returns real data or
/// throws; nothing here invents a value when the platform does not answer, because a
/// fabricated engine status is worse than an error - it would make a broken build look
/// healthy.
class UniqueBridgeClient {
  UniqueBridgeClient._();

  static final instance = UniqueBridgeClient._();

  static const _method = MethodChannel('com.unique/bridge');
  static const _events = EventChannel('com.unique/diagnostics');

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

  /// Writes a diagnostics package and returns where it landed.
  ///
  /// The file is UNIQUE's to keep: it lands in app-private storage and carries nothing
  /// from inside a virtualized app. See `DiagnosticsExport` on the Kotlin side.
  Future<DiagnosticsExportResult> exportDiagnostics() async {
    final result =
        await _method.invokeMapMethod<Object?, Object?>('exportDiagnostics');
    return DiagnosticsExportResult.fromMap(result ?? const {});
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

  /// What this device can offer a virtualized app's Google flows.
  Future<GoogleStatus> googleStatus() async {
    final result = await _method.invokeMapMethod<Object?, Object?>('googleStatus');
    return GoogleStatus.fromMap(result ?? const {});
  }

  Future<List<DiagRecord>> diagnosticsSnapshot() async {
    final result = await _method.invokeListMethod<Object?>('diagnosticsSnapshot');
    return (result ?? const [])
        .map((e) => DiagRecord.fromMap(e as Map<Object?, Object?>))
        .toList();
  }

  Stream<DiagRecord> diagnostics() => _events
      .receiveBroadcastStream()
      .map((e) => DiagRecord.fromMap(e as Map<Object?, Object?>));
}
