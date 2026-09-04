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
