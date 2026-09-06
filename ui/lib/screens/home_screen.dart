import 'package:flutter/material.dart';

import '../l10n/strings.dart';
import '../models/models.dart';
import '../state/app_state.dart';
import '../theme/unique_theme.dart';
import '../widgets/common.dart';
import 'add_app_screen.dart';
import 'app_details_screen.dart';
import 'files_screen.dart';
import 'settings_screen.dart';

/// Home.
///
/// Deliberately spare: the mark, a search affordance, settings, and the user's apps.
/// No description of what UNIQUE is - a user who opened it already knows.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.state});

  final AppState state;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  String _query = '';
  bool _searching = false;
  final _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<VirtualApp> get _visible {
    final apps = widget.state.apps;
    if (_query.isEmpty) return apps;
    final q = _query.toLowerCase();
    return apps
        .where((a) =>
            a.label.toLowerCase().contains(q) ||
            a.packageName.toLowerCase().contains(q))
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final engine = widget.state.engine;
    final s = Strings.of(context);

    return Scaffold(
      body: SafeArea(
        child: CustomScrollView(
          slivers: [
            SliverAppBar(
              pinned: true,
              titleSpacing: UniqueSpace.lg,
              title: _searching
                  ? _SearchField(
                      hint: s.t('home.searchHint'),
                      controller: _searchController,
                      onChanged: (v) => setState(() => _query = v),
                    )
                  : Row(
                      children: [
                        const UniqueMark(size: 26),
                        const SizedBox(width: UniqueSpace.sm),
                        Text(s.t('app.title'), style: theme.appBarTheme.titleTextStyle),
                      ],
                    ),
              actions: [
                IconButton(
                  tooltip: s.t(_searching ? 'home.searchClose' : 'home.search'),
                  icon: Icon(_searching ? Icons.close_rounded : Icons.search_rounded),
                  onPressed: () => setState(() {
                    _searching = !_searching;
                    if (!_searching) {
                      _query = '';
                      _searchController.clear();
                    }
                  }),
                ),
                IconButton(
                  tooltip: s.t('home.settings'),
                  icon: const Icon(Icons.tune_rounded),
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (_) => SettingsScreen(state: widget.state),
                    ),
                  ),
                ),
                const SizedBox(width: UniqueSpace.xs),
              ],
            ),

            // Engine state. Shown only when something is actually wrong or incomplete,
            // so it never becomes furniture the user learns to ignore.
            if (engine != null && !engine.ready)
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(
                    UniqueSpace.lg, UniqueSpace.sm, UniqueSpace.lg, 0),
                sliver: SliverToBoxAdapter(child: _EngineNotice(engine: engine)),
              ),

            // Files is a built-in app and sits in the grid with the others: it belongs
            // to the virtual device the way a file manager belongs to a phone. It is
            // always first, is never filtered out by a search, and has no Remove — a
            // built-in app that could be deleted would leave the space with no way to
            // put a file into it.
            SliverPadding(
              padding: const EdgeInsets.all(UniqueSpace.lg),
              sliver: SliverGrid(
                gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 132,
                  mainAxisSpacing: UniqueSpace.md,
                  crossAxisSpacing: UniqueSpace.md,
                  childAspectRatio: 0.82,
                ),
                delegate: SliverChildBuilderDelegate(
                  (context, index) => index == 0
                      ? _BuiltInCard(
                          label: s.t('files.title'),
                          icon: Icons.folder_rounded,
                          onOpen: _openFiles,
                        )
                      : _AppCard(
                          app: _visible[index - 1],
                          state: widget.state,
                          onOpen: () => _openDetails(_visible[index - 1]),
                        ),
                  childCount: _visible.length + 1,
                ),
              ),
            ),

            if (_visible.isEmpty && _query.isEmpty)
              SliverToBoxAdapter(child: _EmptyState(onAdd: _openAddApp)),
          ],
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _openAddApp,
        icon: const Icon(Icons.add_rounded),
        label: Text(s.t('home.addApp')),
      ),
    );
  }

  void _openFiles() {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => FilesScreen(state: widget.state)),
    );
  }

  void _openAddApp() {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => AddAppScreen(state: widget.state)),
    );
  }

  void _openDetails(VirtualApp app) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => AppDetailsScreen(app: app, state: widget.state),
      ),
    );
  }
}

class _SearchField extends StatelessWidget {
  const _SearchField({
    required this.hint,
    required this.controller,
    required this.onChanged,
  });

  final String hint;
  final TextEditingController controller;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) => TextField(
        controller: controller,
        autofocus: true,
        onChanged: onChanged,
        textInputAction: TextInputAction.search,
        decoration: InputDecoration(hintText: hint, isDense: true),
      );
}

/// Says what the engine cannot do, in the terms the user experiences.
class _EngineNotice extends StatelessWidget {
  const _EngineNotice({required this.engine});

  final EngineStatus engine;

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    if (!engine.supportsArm64) {
      return NoticeBanner(
        tone: NoticeTone.error,
        title: s.t('engine.unsupported.title'),
        message: s.t('engine.unsupported.body'),
      );
    }
    if (!engine.nativeLoaded) {
      return NoticeBanner(
        tone: NoticeTone.error,
        title: s.t('engine.nativeFailed.title'),
        message: engine.nativeLoadError ?? s.t('engine.nativeFailed.body'),
      );
    }
    if (!engine.hiddenApiGranted) {
      return NoticeBanner(
        tone: NoticeTone.error,
        title: s.t('engine.restricted.title'),
        message: s.t('engine.restricted.body'),
      );
    }
    // Something else the engine reports as not ready. This used to say launching was
    // unimplemented, which stopped being true two phases ago and would now send someone
    // looking for a milestone instead of for what Settings actually reports.
    return NoticeBanner(
      tone: NoticeTone.warning,
      title: s.t('engine.degraded.title'),
      message: s.t('engine.degraded.body'),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({required this.onAdd});

  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final s = Strings.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(
          UniqueSpace.xxl, UniqueSpace.md, UniqueSpace.xxl, UniqueSpace.xxl),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Opacity(opacity: 0.35, child: const UniqueMark(size: 72)),
            const SizedBox(height: UniqueSpace.xl),
            Text(s.t('home.empty.title'), style: theme.textTheme.titleLarge),
            const SizedBox(height: UniqueSpace.sm),
            Text(
              s.t('home.empty.body'),
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: UniqueSpace.xl),
            FilledButton(onPressed: onAdd, child: Text(s.t('home.addApp'))),
          ],
        ),
      ),
    );
  }
}

/// A tile for an app UNIQUE itself provides.
///
/// It looks like the others because it is one — the virtual device has a file manager the
/// way a phone does. What it does not have is a long-press menu: *Remove* on the only
/// thing that can put a file into the space would be a way to lock yourself out of it.
class _BuiltInCard extends StatelessWidget {
  const _BuiltInCard({
    required this.label,
    required this.icon,
    required this.onOpen,
  });

  final String label;
  final IconData icon;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(UniqueSpace.radiusMd),
        onTap: onOpen,
        child: Padding(
          padding: const EdgeInsets.symmetric(
              vertical: UniqueSpace.lg, horizontal: UniqueSpace.sm),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: UniqueColors.accentMuted,
                  borderRadius: BorderRadius.circular(UniqueSpace.radiusMd),
                ),
                child: Icon(icon, size: 26, color: Colors.white),
              ),
              const SizedBox(height: UniqueSpace.md),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleMedium,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AppCard extends StatelessWidget {
  const _AppCard({required this.app, required this.state, required this.onOpen});

  final VirtualApp app;
  final AppState state;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final s = Strings.of(context);
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(UniqueSpace.radiusMd),
        onTap: onOpen,
        onLongPress: () => _showMenu(context),
        child: Padding(
          padding: const EdgeInsets.symmetric(
              vertical: UniqueSpace.lg, horizontal: UniqueSpace.sm),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Stack(
                clipBehavior: Clip.none,
                children: [
                  AppIconTile(label: app.label, bytes: app.icon, size: 48),
                  if (app.running)
                    Positioned(
                      right: -2,
                      bottom: -2,
                      child: Container(
                        width: 12,
                        height: 12,
                        decoration: BoxDecoration(
                          color: UniqueColors.success,
                          shape: BoxShape.circle,
                          border: Border.all(color: theme.colorScheme.surface, width: 2),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: UniqueSpace.md),
              Text(
                app.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleMedium,
              ),
              const SizedBox(height: 2),
              Text(
                app.profileLabel(s),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodySmall,
              ),
            ],
          ),
        ),
      ),
    );
  }

  static Future<void> _act(
    BuildContext context,
    Future<EngineOutcome> Function() action,
    String successMessage,
  ) async {
    final messenger = ScaffoldMessenger.of(context);
    final s = Strings.of(context);
    final fallback = s.t('common.failed');
    final result = await action();
    messenger.showSnackBar(SnackBar(
      content: Text(result.ok ? successMessage : result.describe(s, fallback)),
    ));
  }

  void _showMenu(BuildContext context) {
    final s = Strings.of(context);
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      backgroundColor: Theme.of(context).colorScheme.surface,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.info_outline_rounded),
              title: Text(s.t('home.menu.details')),
              onTap: () {
                Navigator.pop(context);
                onOpen();
              },
            ),
            ListTile(
              leading: const Icon(Icons.copy_all_rounded),
              title: Text(s.t('home.menu.clone')),
              subtitle: Text(s.t('home.menu.cloneBody')),
              onTap: () {
                Navigator.pop(context);
                _act(context, () => state.clone(app), s.t('home.cloned'));
              },
            ),
            ListTile(
              leading: Icon(Icons.delete_outline_rounded, color: UniqueColors.error),
              title: Text(s.t('home.menu.remove'),
                  style: TextStyle(color: UniqueColors.error)),
              onTap: () {
                Navigator.pop(context);
                _act(context, () => state.remove(app), s.t('home.removed'));
              },
            ),
            const SizedBox(height: UniqueSpace.sm),
          ],
        ),
      ),
    );
  }
}
