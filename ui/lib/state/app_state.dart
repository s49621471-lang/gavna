import 'package:flutter/foundation.dart';

import '../bridge/unique_bridge.dart';
import '../l10n/strings.dart';
import '../models/models.dart';

enum LoadState { idle, loading, ready, failed }

/// Application state.
///
/// A plain [ChangeNotifier] rather than a state-management package: the UI has four
/// screens and one source of truth, and adding a dependency for that would be
/// architecture for its own sake. If the surface grows past this, the notifier is easy to
/// replace precisely because nothing else knows how it works.
class AppState extends ChangeNotifier {
  AppState({UniqueBridgeClient? bridge})
      : _bridge = bridge ?? UniqueBridgeClient.instance;

  final UniqueBridgeClient _bridge;

  LoadState _status = LoadState.idle;
  LoadState get status => _status;

  String? _error;
  String? get error => _error;

  EngineStatus? _engine;
  EngineStatus? get engine => _engine;

  List<VirtualApp> _apps = const [];
  List<VirtualApp> get apps => _apps;

  final List<DiagRecord> _diagnostics = [];
  List<DiagRecord> get diagnostics => List.unmodifiable(_diagnostics);

  final Map<String, Uint8List?> _iconCache = {};

  bool _dynamicColor = true;
  bool get dynamicColor => _dynamicColor;
  set dynamicColor(bool value) {
    if (_dynamicColor == value) return;
    _dynamicColor = value;
    _bridge.setUiSetting('dynamicColor', value);
    notifyListeners();
  }

  bool _reducedMotion = false;
  bool get reducedMotion => _reducedMotion;
  set reducedMotion(bool value) {
    if (_reducedMotion == value) return;
    _reducedMotion = value;
    _bridge.setUiSetting('reducedMotion', value);
    notifyListeners();
  }

  /// The interface language, `system` unless the user chose otherwise.
  ///
  /// Persisted through the engine rather than held here: a language that resets every
  /// time the app is opened is not a setting, it is a fault.
  AppLanguage _language = AppLanguage.system;
  AppLanguage get language => _language;
  set language(AppLanguage value) {
    if (_language == value) return;
    _language = value;
    _bridge.setUiSetting('language', value.code);
    notifyListeners();
  }

  Future<void> _loadUiSettings() async {
    final settings = await _bridge.uiSettings();
    if (settings.isEmpty) return;
    _language = AppLanguage.fromCode(settings['language'] as String?);
    _dynamicColor = settings['dynamicColor'] as bool? ?? _dynamicColor;
    _reducedMotion = settings['reducedMotion'] as bool? ?? _reducedMotion;
  }

  Future<void> load() async {
    _status = LoadState.loading;
    notifyListeners();
    try {
      await _loadUiSettings();
      _engine = await _bridge.engineStatus();
      await _refreshInstances();
      _status = LoadState.ready;
      _error = null;
    } catch (e) {
      _status = LoadState.failed;
      _error = e.toString();
    }
    notifyListeners();
    _listenToDiagnostics();
  }

  /// Re-reads the instance list from the engine and attaches icons.
  ///
  /// Icons come from the host PackageManager when the app is also installed there, and
  /// are simply absent otherwise - a virtual app usually is not installed, so the
  /// monogram fallback is the normal case, not an error path.
  Future<void> _refreshInstances() async {
    final apps = await _bridge.listInstances();
    _apps = await Future.wait(apps.map((a) async {
      final bytes = await icon(a.packageName);
      return bytes == null ? a : a.copyWith(icon: bytes);
    }));
  }

  Future<void> refresh() async {
    try {
      await _refreshInstances();
      _error = null;
    } catch (e) {
      _error = e.toString();
    }
    notifyListeners();
  }

  // ---- actions ------------------------------------------------------------------

  Future<EngineOutcome> importInstalled(String packageName) async {
    final result = await _bridge.importInstalled(packageName);
    if (result.ok) await refresh();
    return result;
  }

  Future<EngineOutcome> importApk(List<String> paths) async {
    final result = await _bridge.importApk(paths);
    if (result.ok) await refresh();
    return result;
  }

  Future<EngineOutcome> importApkFromPicker() async {
    final result = await _bridge.importApkFromPicker();
    if (result.ok && !result.cancelled) await refresh();
    return result;
  }

  Future<EngineOutcome> clone(VirtualApp app) async {
    final result = await _bridge.cloneInstance(app.packageName);
    if (result.ok) await refresh();
    return result;
  }

  Future<EngineOutcome> launch(VirtualApp app) => _bridge.launchInstance(app.vuid);

  Future<EngineOutcome> remove(VirtualApp app) async {
    final result = await _bridge.removeInstance(app.vuid);
    if (result.ok) await refresh();
    return result;
  }

  Future<EngineOutcome> clearCache(VirtualApp app) async {
    final result = await _bridge.clearCache(app.vuid);
    if (result.ok) await refresh();
    return result;
  }

  Future<EngineOutcome> clearData(VirtualApp app) async {
    final result = await _bridge.clearData(app.vuid);
    if (result.ok) await refresh();
    return result;
  }

  Future<DiagnosticsExportResult> exportDiagnostics() =>
      _bridge.exportDiagnostics();

  Future<GoogleStatus> googleStatus() => _bridge.googleStatus();

  Future<List<ReportSection>> deviceReport() => _bridge.deviceReport();

  Future<List<ChecklistStep>> checklist() => _bridge.checklist();

  Future<List<ChecklistStep>> setChecklistStep(
    String id,
    StepVerdict verdict,
    String note,
  ) =>
      _bridge.setChecklistStep(id, verdict, note);

  Future<List<ChecklistStep>> resetChecklist() => _bridge.resetChecklist();

  Future<DiagnosticsExportResult> shareDiagnostics() => _bridge.shareDiagnostics();

  Future<List<GoogleRoute>> googleRouting(int vuid) => _bridge.googleRouting(vuid);

  Future<List<InstancePermission>> instancePermissions(int vuid) =>
      _bridge.instancePermissions(vuid);

  Future<EngineOutcome> setInstancePermission(int vuid, String group, bool granted) =>
      _bridge.setInstancePermission(vuid, group, granted);

  void _listenToDiagnostics() {
    _bridge.diagnostics().listen((record) {
      _diagnostics.insert(0, record);
      if (_diagnostics.length > 500) _diagnostics.removeLast();
      notifyListeners();
    }, onError: (_) {});
  }

  Future<List<InstalledApp>> installedApps({bool includeSystem = false}) =>
      _bridge.listInstalledApps(includeSystem: includeSystem);

  /// Icons are fetched once per package and cached: Add App shows hundreds of rows and
  /// re-decoding on every scroll frame is the difference between smooth and not.
  Future<Uint8List?> icon(String packageName) async {
    if (_iconCache.containsKey(packageName)) return _iconCache[packageName];
    final bytes = await _bridge.appIcon(packageName);
    _iconCache[packageName] = bytes;
    return bytes;
  }
}
