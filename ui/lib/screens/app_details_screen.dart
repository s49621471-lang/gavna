import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/models.dart';
import '../state/app_state.dart';
import '../theme/unique_theme.dart';
import '../widgets/common.dart';
import 'settings_screen.dart' show DiagnosticsScreen;

/// App Details.
///
/// One screen, seven sections, in the order a user needs them: what this is, what it may
/// do, what it stores, who it says it is, how Google works for it, what is adjusted for
/// it, and what went wrong.
class AppDetailsScreen extends StatefulWidget {
  const AppDetailsScreen({super.key, required this.app, required this.state});

  final VirtualApp app;
  final AppState state;

  @override
  State<AppDetailsScreen> createState() => _AppDetailsScreenState();
}

class _AppDetailsScreenState extends State<AppDetailsScreen> {
  VirtualApp get app => widget.app;
  AppState get state => widget.state;

  /// The permission groups this app actually asks for. Null while they are being read;
  /// an empty list means it asks for none, which is a different thing and shown as such.
  List<InstancePermission>? _permissions;
  GoogleStatus? _google;
  List<GoogleRoute>? _routes;
  String? _busyGroup;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final permissions = await state.instancePermissions(app.vuid);
    final google = await state.googleStatus();
    final routes = await state.googleRouting(app.vuid);
    if (!mounted) return;
    setState(() {
      _permissions = permissions;
      _google = google;
      _routes = routes;
    });
  }

  Future<void> _togglePermission(InstancePermission permission, bool granted) async {
    setState(() => _busyGroup = permission.group);
    final result =
        await state.setInstancePermission(app.vuid, permission.group, granted);
    final permissions = await state.instancePermissions(app.vuid);
    if (!mounted) return;
    setState(() {
      _busyGroup = null;
      _permissions = permissions;
    });
    if (!result.ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(result.message ?? 'That did not work.')),
      );
    }
  }

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
                    if (_permissions == null)
                      const SectionRow(label: 'Reading...', value: '')
                    else if (_permissions!.isEmpty)
                      const SectionRow(
                        label: 'None requested',
                        value: 'This app asks for no runtime permissions',
                      )
                    else
                      // Only the groups this app's own manifest asks for. Offering Camera
                      // to an app that cannot use it is a lie about the app.
                      for (final p in _permissions!) ...[
                        SectionRow(
                          label: p.label,
                          value: p.blockedByHost
                              ? 'Grant it to UNIQUE first - it cannot pass on what it '
                                  'does not hold'
                              : p.granted
                                  ? 'Allowed'
                                  : 'Not allowed',
                          valueColor:
                              p.blockedByHost ? UniqueColors.warning : null,
                          trailing: _busyGroup == p.group
                              ? const SizedBox(
                                  width: 16,
                                  height: 16,
                                  child: CircularProgressIndicator(strokeWidth: 2))
                              : Switch(
                                  value: p.granted,
                                  // Disabled, not merely off: UNIQUE can only narrow what
                                  // it holds, so a switch that moved here would do nothing.
                                  onChanged: p.blockedByHost
                                      ? null
                                      : (v) => _togglePermission(p, v),
                                ),
                        ),
                        if (p != _permissions!.last) const Divider(),
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
                    // Read from the device. Nothing here claims a flow works, because
                    // none of them is implemented yet and saying otherwise would send a
                    // user chasing a failure that is not theirs.
                    SectionRow(
                      label: 'Play services on this device',
                      value: _google == null
                          ? 'Reading...'
                          : !_google!.gmsPresent
                              ? 'Not installed'
                              : _google!.presentButUnusable
                                  ? 'Installed but not usable'
                                  : 'Available  -  ${_google!.gmsVersionName}',
                      trailing: _StatusDot(
                        tone: _google == null
                            ? NoticeTone.info
                            : _google!.hostGmsAvailable
                                ? NoticeTone.info
                                : NoticeTone.warning,
                      ),
                    ),
                    const Divider(),
                    const SectionRow(
                      label: 'Sign-in flows',
                      value: 'Not implemented yet. What follows is how each flow *would* '
                          'be routed for this app, and on what evidence',
                      trailing: _StatusDot(tone: NoticeTone.warning),
                    ),
                    // The router's real decisions for this app's own manifest, not a
                    // fixed list. Two apps get different answers, which is the point.
                    if (_routes == null)
                      const SectionRow(label: 'Reading...', value: '')
                    else
                      for (final r in _routes!) ...[
                        const Divider(),
                        SectionRow(
                          label: r.flowLabel,
                          value: '${r.modeLabel}  -  ${r.why}',
                          valueColor: r.unsupported ? UniqueColors.warning : null,
                          trailing: _StatusDot(
                            tone: r.unsupported ? NoticeTone.error : NoticeTone.info,
                          ),
                        ),
                      ],
                  ],
                ),

                SectionCard(
                  title: 'Diagnostics',
                  children: [
                    SectionRow(
                      label: 'Recent events',
                      value: '${state.diagnostics.length} recorded',
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => DiagnosticsScreen(state: state),
                        ),
                      ),
                      trailing: const Icon(Icons.chevron_right_rounded, size: 20),
                    ),
                    const Divider(),
                    SectionRow(
                      label: _exporting
                          ? 'Collecting from every running app...'
                          : 'Export diagnostic package',
                      value: _exportSummary ??
                          'UNIQUE\'s logs and this device. Nothing from inside the app: '
                              'no databases, no cookies, no tokens',
                      onTap: _exporting ? null : _export,
                      trailing: _exporting
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.ios_share_rounded, size: 18),
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

  bool _exporting = false;
  String? _exportSummary;

  Future<void> _export() async {
    setState(() {
      _exporting = true;
      _exportSummary = null;
    });
    final result = await state.exportDiagnostics();
    if (!mounted) return;
    setState(() {
      _exporting = false;
      _exportSummary = result.ok
          ? '${result.name}  -  ${result.lines} lines from '
              '${result.processes + 1} processes'
          : result.message ?? 'Export failed';
    });
    if (result.ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Saved to ${result.path}')),
      );
    }
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
