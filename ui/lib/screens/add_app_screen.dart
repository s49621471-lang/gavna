import 'dart:typed_data';

import 'package:flutter/material.dart';

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

  @override
  void dispose() {
    _tabs.dispose();
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Add App'),
        bottom: TabBar(
          controller: _tabs,
          dividerColor: Colors.transparent,
          tabs: const [Tab(text: 'Installed'), Tab(text: 'APK')],
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
                hintText: 'Search',
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
                title: 'Could not list applications',
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
                    Text('${apps.length} apps',
                        style: Theme.of(context).textTheme.bodySmall),
                    const Spacer(),
                    Text('System apps',
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
            const NoticeBanner(
              tone: NoticeTone.info,
              title: 'Supported files',
              message: 'A single .apk, or a base APK together with its split APKs. '
                  'UNIQUE keeps the arm64-v8a split and every feature split, and reports '
                  'anything it cannot run before copying it.',
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
                  Text('Choose an APK from your files',
                      style: Theme.of(context).textTheme.bodyMedium),
                ],
              ),
            ),
            const Spacer(),
            FilledButton.icon(
              // TODO(phase-2): open the system picker and run PackageInstaller.import.
              // Disabled rather than wired to a no-op, so the control never lies.
              onPressed: null,
              icon: const Icon(Icons.file_open_rounded),
              label: const Text('Select APK'),
            ),
            const SizedBox(height: UniqueSpace.sm),
            Text(
              'Importing lands in the next milestone.',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall,
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
    final result = await state.importInstalled(app.packageName);
    if (!mounted) return;
    setState(() => _busy = false);
    messenger.showSnackBar(SnackBar(
      content: Text(result.ok
          ? '${app.label} added'
          : (result.message ?? 'Could not add ${app.label}.')),
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
