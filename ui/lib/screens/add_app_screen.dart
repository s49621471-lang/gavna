import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../l10n/strings.dart';
import '../models/models.dart';
import '../state/app_state.dart';
import '../theme/unique_theme.dart';
import '../widgets/common.dart';

/// Add App.
///
/// Two sources, one list style. The installed list can run to several hundred rows on a
/// real device, so icons load lazily and are cached in [AppState]; the list itself is
/// built lazily by the sliver.
class AddAppScreen extends StatefulWidget {
  const AddAppScreen({super.key, required this.state});

  final AppState state;

  @override
  State<AddAppScreen> createState() => _AddAppScreenState();
}

class _AddAppScreenState extends State<AddAppScreen> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 2, vsync: this);
  final _searchController = TextEditingController();

  String _query = '';
  bool _includeSystem = false;
  Future<List<InstalledApp>>? _future;

  bool _importing = false;
  bool _pickerFailed = false;
  String? _pickerMessage;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() {
    setState(() {
      _future = widget.state.installedApps(includeSystem: _includeSystem);
    });
  }

  /// Picks APK files and imports them as one app.
  ///
  /// Everything the user selected goes to the engine in a single call: a base APK and
  /// its splits are one package, and importing them one at a time would either fail or,
  /// worse, produce an app missing the split it needed.
  Future<void> _pickAndImport() async {
    setState(() {
      _importing = true;
      _pickerFailed = false;
      _pickerMessage = null;
    });
    final result = await widget.state.importApkFromPicker();
    if (!mounted) return;
    final s = Strings.of(context);
    setState(() {
      _importing = false;
      _pickerFailed = !result.ok;
      _pickerMessage = result.cancelled
          ? null
          : result.ok
              ? s.t('add.added', {'app': s.t('add.tab.apk')})
              : result.message ?? s.t('add.failed', {'app': s.t('add.tab.apk')});
    });
    if (result.ok && !result.cancelled && mounted) Navigator.of(context).pop();
  }

  @override
  void dispose() {
    _tabs.dispose();
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(s.t('add.title')),
        bottom: TabBar(
          controller: _tabs,
          dividerColor: Colors.transparent,
          tabs: [
            Tab(text: s.t('add.tab.installed')),
            Tab(text: s.t('add.tab.apk')),
          ],
        ),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
                UniqueSpace.lg, UniqueSpace.md, UniqueSpace.lg, UniqueSpace.sm),
            child: TextField(
              controller: _searchController,
              onChanged: (v) => setState(() => _query = v),
              decoration: InputDecoration(
                hintText: s.t('add.searchHint'),
                prefixIcon: const Icon(Icons.search_rounded, size: 20),
                suffixIcon: _query.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.close_rounded, size: 18),
                        onPressed: () {
                          _searchController.clear();
                          setState(() => _query = '');
                        },
                      ),
                isDense: true,
              ),
            ),
          ),
          Expanded(
            child: TabBarView(
              controller: _tabs,
              children: [_installedTab(), _apkTab()],
            ),
          ),
        ],
      ),
    );
  }

  Widget _installedTab() => FutureBuilder<List<InstalledApp>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator(strokeWidth: 2));
          }
          if (snapshot.hasError) {
            return Padding(
              padding: const EdgeInsets.all(UniqueSpace.lg),
              child: NoticeBanner(
                tone: NoticeTone.error,
                title: Strings.of(context).t('add.listFailed'),
                message: '${snapshot.error}',
              ),
            );
          }
          final all = snapshot.data ?? const <InstalledApp>[];
          final q = _query.toLowerCase();
          final apps = q.isEmpty
              ? all
              : all
                  .where((a) =>
                      a.label.toLowerCase().contains(q) ||
                      a.packageName.toLowerCase().contains(q))
                  .toList();

          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: UniqueSpace.lg),
                child: Row(
                  children: [
                    Text('${apps.length}',
                        style: Theme.of(context).textTheme.bodySmall),
                    const Spacer(),
                    Text(Strings.of(context).t('add.systemApps'),
                        style: Theme.of(context).textTheme.bodySmall),
                    const SizedBox(width: UniqueSpace.sm),
                    Switch(
                      value: _includeSystem,
                      onChanged: (v) {
                        _includeSystem = v;
                        _reload();
                      },
                    ),
                  ],
                ),
              ),
              Expanded(
                child: ListView.builder(
                  padding: const EdgeInsets.fromLTRB(
                      UniqueSpace.lg, UniqueSpace.sm, UniqueSpace.lg, UniqueSpace.xxl),
                  itemCount: apps.length,
                  itemBuilder: (context, i) => _InstalledRow(
                    app: apps[i],
                    state: widget.state,
                  ),
                ),
              ),
            ],
          );
        },
      );

  Widget _apkTab() => Padding(
        padding: const EdgeInsets.all(UniqueSpace.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            NoticeBanner(
              tone: NoticeTone.info,
              title: Strings.of(context).t('add.supportedTitle'),
              message: Strings.of(context).t('add.supportedBody'),
            ),
            const Spacer(),
            Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.folder_open_rounded,
                      size: 44,
                      color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.3)),
                  const SizedBox(height: UniqueSpace.lg),
                  Text(Strings.of(context).t('add.chooseApk'),
                      style: Theme.of(context).textTheme.bodyMedium),
                ],
              ),
            ),
            const Spacer(),
            FilledButton.icon(
              onPressed: _importing ? null : _pickAndImport,
              icon: _importing
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.file_open_rounded),
              label: Text(Strings.of(context)
                  .t(_importing ? 'add.importing' : 'add.selectApk')),
            ),
            const SizedBox(height: UniqueSpace.sm),
            Text(
              _pickerMessage ?? Strings.of(context).t('add.splitHint'),
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: _pickerFailed
                        ? Theme.of(context).colorScheme.error
                        : null,
                  ),
            ),
          ],
        ),
      );
}

class _InstalledRow extends StatefulWidget {
  const _InstalledRow({required this.app, required this.state});

  final InstalledApp app;
  final AppState state;

  @override
  State<_InstalledRow> createState() => _InstalledRowState();
}

class _InstalledRowState extends State<_InstalledRow> {
  bool _busy = false;

  InstalledApp get app => widget.app;
  AppState get state => widget.state;

  Future<void> _import(BuildContext context) async {
    if (_busy) return;
    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    // Resolved before the await, like the messenger and the navigator beside it: a
    // BuildContext read after an async gap is a context that may no longer be mounted.
    final s = Strings.of(context);
    final result = await state.importInstalled(app.packageName);
    if (!mounted) return;
    setState(() => _busy = false);
    messenger.showSnackBar(SnackBar(
      content: Text(result.ok
          ? s.t('add.added', {'app': app.label})
          : (result.message ?? s.t('add.failed', {'app': app.label}))),
    ));
    if (result.ok) navigator.pop();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final blocked = app.blockedReason;
    return Opacity(
      opacity: blocked == null ? 1 : 0.5,
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: UniqueSpace.xs),
        leading: FutureBuilder<Uint8List?>(
          future: state.icon(app.packageName),
          builder: (context, snapshot) =>
              AppIconTile(label: app.label, bytes: snapshot.data, size: 40),
        ),
        title: Text(app.label,
            maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.titleMedium),
        subtitle: Text(
          blocked ?? app.packageName,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.bodySmall?.copyWith(
            color: blocked == null ? null : UniqueColors.warning,
          ),
        ),
        trailing: _busy
            ? const SizedBox(
                width: 18, height: 18,
                child: CircularProgressIndicator(strokeWidth: 2))
            : blocked == null
                ? const Icon(Icons.add_circle_outline_rounded, size: 22)
                : const Icon(Icons.block_rounded, size: 20),
        onTap: blocked == null ? () => _import(context) : null,
      ),
    );
  }
}
