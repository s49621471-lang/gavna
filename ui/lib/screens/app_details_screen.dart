import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/models.dart';
import '../state/app_state.dart';
import '../theme/unique_theme.dart';
import '../widgets/common.dart';

/// App Details.
///
/// One screen, seven sections, in the order a user needs them: what this is, what it may
/// do, what it stores, who it says it is, how Google works for it, what is adjusted for
/// it, and what went wrong.
class AppDetailsScreen extends StatelessWidget {
  const AppDetailsScreen({super.key, required this.app, required this.state});

  final VirtualApp app;
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final launchable = state.engine?.virtualLaunchImplemented ?? false;

    return Scaffold(
      body: CustomScrollView(
        slivers: [
          SliverAppBar(
            pinned: true,
            expandedHeight: 168,
            flexibleSpace: FlexibleSpaceBar(
              titlePadding: const EdgeInsets.only(
                  left: UniqueSpace.lg, bottom: UniqueSpace.md, right: UniqueSpace.lg),
              title: Text(app.label, style: theme.appBarTheme.titleTextStyle),
              background: Padding(
                padding: const EdgeInsets.only(bottom: 44),
                child: Center(
                  child: AppIconTile(label: app.label, bytes: app.icon, size: 64),
                ),
              ),
            ),
          ),
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(
                UniqueSpace.lg, 0, UniqueSpace.lg, UniqueSpace.xxl),
            sliver: SliverList.list(
              children: [
                const SizedBox(height: UniqueSpace.sm),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton.icon(
                        onPressed: launchable
                            ? () => _run(context, () => state.launch(app), 'Launching…')
                            : null,
                        icon: const Icon(Icons.play_arrow_rounded),
                        label: const Text('Launch'),
                      ),
                    ),
                    const SizedBox(width: UniqueSpace.md),
                    IconButton.filledTonal(
                      onPressed: null,
                      icon: const Icon(Icons.stop_rounded),
                      tooltip: 'Stop',
                    ),
                  ],
                ),
                if (!launchable) ...[
                  const SizedBox(height: UniqueSpace.md),
                  const NoticeBanner(
                    tone: NoticeTone.warning,
                    title: 'Launch unavailable',
                    message: 'Running virtual apps is part of the next milestone.',
                  ),
                ],

                SectionCard(
                  title: 'General',
                  children: [
                    SectionRow(label: 'Package', value: app.packageName),
                    const Divider(),
                    SectionRow(label: 'Version code', value: '${app.versionCode}'),
                    const Divider(),
                    SectionRow(label: 'Instance', value: app.profileName),
                  ],
                ),

                SectionCard(
                  title: 'Permissions',
                  children: [
                    for (final p in const [
                      ('Camera', Icons.photo_camera_outlined),
                      ('Microphone', Icons.mic_none_rounded),
                      ('Location', Icons.location_on_outlined),
                      ('Files', Icons.folder_outlined),
                      ('Notifications', Icons.notifications_none_rounded),
                    ]) ...[
                      SectionRow(
                        label: p.$1,
                        trailing: Switch(value: false, onChanged: null),
                      ),
                      if (p.$1 != 'Notifications') const Divider(),
                    ],
                  ],
                ),

                SectionCard(
                  title: 'Storage',
                  children: [
                    SectionRow(label: 'Data', value: formatBytes(app.dataBytes)),
                    const Divider(),
                    SectionRow(label: 'Cache', value: formatBytes(app.cacheBytes)),
                    const Divider(),
                    SectionRow(label: 'External', value: formatBytes(app.externalBytes)),
                    const Divider(),
                    SectionRow(
                      label: 'Clear cache',
                      onTap: () => _run(context, () => state.clearCache(app), 'Cache cleared'),
                      trailing: const Icon(Icons.chevron_right_rounded, size: 20),
                    ),
                    const Divider(),
                    SectionRow(
                      label: 'Clear data',
                      value: 'Removes everything this instance stores',
                      valueColor: UniqueColors.warning,
                      onTap: () => _confirmClearData(context),
                      trailing: const Icon(Icons.chevron_right_rounded, size: 20),
                    ),
                  ],
                ),

                SectionCard(
                  title: 'Device profile',
                  children: [
                    SectionRow(
                      label: 'Android ID',
                      value: app.androidId,
                      monospaceValue: true,
                      trailing: IconButton(
                        tooltip: 'Copy',
                        icon: const Icon(Icons.copy_rounded, size: 18),
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: app.androidId));
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('Android ID copied')),
                          );
                        },
                      ),
                    ),
                    const Divider(),
                    const Divider(),
                    SectionRow(
                      label: 'Instance ID',
                      value: app.instanceId,
                      monospaceValue: true,
                    ),
                    const Divider(),
                    SectionRow(
                      label: 'Generation',
                      value: '${app.generation}',
                    ),
                    const Divider(),
                    SectionRow(
                      label: 'Regenerate',
                      value: 'Not available yet - lands with device profiles',
                      trailing: const Icon(Icons.refresh_rounded, size: 20),
                    ),
                  ],
                ),

                SectionCard(
                  title: 'Google',
                  children: [
                    SectionRow(
                      label: 'Sign in with Google',
                      value: 'Handled by this device\'s Google Play services',
                      trailing: const _StatusDot(tone: NoticeTone.info),
                    ),
                    const Divider(),
                    SectionRow(
                      label: 'Google Sign-In (legacy)',
                      value: 'Needs in-space Google Play services',
                      trailing: const _StatusDot(tone: NoticeTone.warning),
                    ),
                    const Divider(),
                    SectionRow(
                      label: 'Play Games',
                      value: 'Not possible: requires an attested app identity',
                      trailing: const _StatusDot(tone: NoticeTone.error),
                    ),
                  ],
                ),

                SectionCard(
                  title: 'Diagnostics',
                  children: [
                    SectionRow(
                      label: 'Recent events',
                      value: '${state.diagnostics.length} recorded',
                      onTap: () {},
                      trailing: const Icon(Icons.chevron_right_rounded, size: 20),
                    ),
                    const Divider(),
                    SectionRow(
                      label: 'Export diagnostic package',
                      value: 'Tokens, cookies and account names are removed',
                      onTap: () {},
                      trailing: const Icon(Icons.ios_share_rounded, size: 18),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Runs an engine action and reports its real outcome. A failure is shown with the
  /// engine's own message rather than a generic one - the engine knows why.
  static Future<void> _run(
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

  void _confirmClearData(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Clear ${app.label} data?'),
        content: Text(
          'Everything ${app.profileName} has stored is deleted: files, databases and '
          'settings. Other instances of this app are not affected.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () {
              Navigator.pop(dialogContext);
              _run(context, () => state.clearData(app), 'Data cleared');
            },
            child: const Text('Clear'),
          ),
        ],
      ),
    );
  }
}

class _StatusDot extends StatelessWidget {
  const _StatusDot({required this.tone});

  final NoticeTone tone;

  @override
  Widget build(BuildContext context) {
    final color = switch (tone) {
      NoticeTone.info => UniqueColors.success,
      NoticeTone.warning => UniqueColors.warning,
      NoticeTone.error => UniqueColors.error,
    };
    return Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(color: color, shape: BoxShape.circle),
    );
  }
}
