import 'package:flutter/material.dart';

import '../models/models.dart';
import '../state/app_state.dart';
import '../theme/unique_theme.dart';
import '../widgets/common.dart';
import 'add_app_screen.dart';
import 'app_details_screen.dart';
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

    return Scaffold(
      body: SafeArea(
        child: CustomScrollView(
          slivers: [
            SliverAppBar(
              pinned: true,
              titleSpacing: UniqueSpace.lg,
              title: _searching
                  ? _SearchField(
                      controller: _searchController,
                      onChanged: (v) => setState(() => _query = v),
                    )
                  : Row(
                      children: [
                        const UniqueMark(size: 26),
                        const SizedBox(width: UniqueSpace.sm),
                        Text('Unique', style: theme.appBarTheme.titleTextStyle),
                      ],
                    ),
              actions: [
                IconButton(
                  tooltip: _searching ? 'Close search' : 'Search',
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
                  tooltip: 'Settings',
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

            if (_visible.isEmpty)
              SliverFillRemaining(
                hasScrollBody: false,
                child: _EmptyState(onAdd: _openAddApp),
              )
            else
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
                    (context, index) => _AppCard(
                      app: _visible[index],
                      state: widget.state,
                      onOpen: () => _openDetails(_visible[index]),
                    ),
                    childCount: _visible.length,
                  ),
                ),
              ),
          ],
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _openAddApp,
        icon: const Icon(Icons.add_rounded),
        label: const Text('Add App'),
      ),
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
  const _SearchField({required this.controller, required this.onChanged});

  final TextEditingController controller;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) => TextField(
        controller: controller,
        autofocus: true,
        onChanged: onChanged,
        textInputAction: TextInputAction.search,
        decoration: const InputDecoration(
          hintText: 'Search apps',
          isDense: true,
        ),
      );
}

/// Says what the engine cannot do, in the terms the user experiences.
class _EngineNotice extends StatelessWidget {
  const _EngineNotice({required this.engine});

  final EngineStatus engine;

  @override
  Widget build(BuildContext context) {
    if (!engine.supportsArm64) {
      return const NoticeBanner(
        tone: NoticeTone.error,
        title: 'Unsupported device',
        message: 'UNIQUE runs 64-bit ARM applications. This device does not report '
            'arm64-v8a support.',
      );
    }
    if (!engine.nativeLoaded) {
      return NoticeBanner(
        tone: NoticeTone.error,
        title: 'Engine library did not load',
        message: engine.nativeLoadError ?? 'libunique_native could not be loaded.',
      );
    }
    if (!engine.hiddenApiGranted) {
      return NoticeBanner(
        tone: NoticeTone.error,
        title: 'Restricted platform access',
        message: 'UNIQUE could not obtain the platform access it needs on this device, '
            'so virtual apps cannot be launched. Details are in Settings, Diagnostics.',
      );
    }
    return const NoticeBanner(
      tone: NoticeTone.warning,
      title: 'Launching is not available in this build',
      message: 'The interface, importer and device profiles are in place. Running a '
          'virtual app is part of the next milestone, so Launch is disabled rather '
          'than failing silently.',
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({required this.onAdd});

  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(UniqueSpace.xxl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Opacity(opacity: 0.35, child: const UniqueMark(size: 72)),
            const SizedBox(height: UniqueSpace.xl),
            Text('No apps yet', style: theme.textTheme.titleLarge),
            const SizedBox(height: UniqueSpace.sm),
            Text(
              'Add an installed app or an APK to give it its own space.',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: UniqueSpace.xl),
            FilledButton(onPressed: onAdd, child: const Text('Add App')),
          ],
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
                app.profileName,
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
    final result = await action();
    messenger.showSnackBar(SnackBar(
      content: Text(result.ok ? successMessage : (result.message ?? 'That did not work.')),
    ));
  }

  void _showMenu(BuildContext context) {
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
              title: const Text('Details'),
              onTap: () {
                Navigator.pop(context);
                onOpen();
              },
            ),
            ListTile(
              leading: const Icon(Icons.copy_all_rounded),
              title: const Text('Clone'),
              subtitle: const Text('Create another independent instance'),
              onTap: () {
                Navigator.pop(context);
                _act(context, () => state.clone(app), 'Instance created');
              },
            ),
            ListTile(
              leading: Icon(Icons.delete_outline_rounded, color: UniqueColors.error),
              title: Text('Remove', style: TextStyle(color: UniqueColors.error)),
              onTap: () {
                Navigator.pop(context);
                _act(context, () => state.remove(app), 'Removed');
              },
            ),
            const SizedBox(height: UniqueSpace.sm),
          ],
        ),
      ),
    );
  }
}
