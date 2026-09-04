import 'package:flutter/foundation.dart';

import '../bridge/unique_bridge.dart';
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
    notifyListeners();
  }

  bool _reducedMotion = false;
  bool get reducedMotion => _reducedMotion;
  set reducedMotion(bool value) {
    if (_reducedMotion == value) return;
    _reducedMotion = value;
    notifyListeners();
  }

  Future<void> load() async {
    _status = LoadState.loading;
    notifyListeners();
    try {
      _engine = await _bridge.engineStatus();
      // Instances come from the state database once the VirtualCore server exposes its
      // interface (phase 2). Until then Home shows the empty state, which is accurate:
      // there genuinely are no instances.
      _apps = const [];
      _status = LoadState.ready;
      _error = null;
    } catch (e) {
      _status = LoadState.failed;
      _error = e.toString();
    }
    notifyListeners();
    _listenToDiagnostics();
  }

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
