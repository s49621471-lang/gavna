import 'package:flutter/material.dart';

import '../models/models.dart';
import '../state/app_state.dart';
import '../theme/unique_theme.dart';
import '../widgets/common.dart';
import 'device_test_screen.dart';

/// Settings.
///
/// Short by design. Everything here changes something observable; nothing is a
/// preference for its own sake.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.state});

  final AppState state;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  @override
  Widget build(BuildContext context) {
    final engine = widget.state.engine;
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(
            UniqueSpace.lg, 0, UniqueSpace.lg, UniqueSpace.xxl),
        children: [
          SectionCard(
            title: 'Appearance',
            children: [
              SectionRow(
                label: 'Material You colours',
                value: 'Follow the system wallpaper accent',
                trailing: Switch(
                  value: widget.state.dynamicColor,
                  onChanged: (v) => setState(() => widget.state.dynamicColor = v),
                ),
              ),
              const Divider(),
              SectionRow(
                label: 'Reduce motion',
                value: 'Shorter transitions',
                trailing: Switch(
                  value: widget.state.reducedMotion,
                  onChanged: (v) => setState(() => widget.state.reducedMotion = v),
                ),
              ),
            ],
          ),

          SectionCard(
            title: 'Engine',
            children: [
              SectionRow(
                label: 'Platform access',
                value: engine == null
                    ? 'Unknown'
                    : engine.hiddenApiGranted
                        ? 'Granted'
                        : 'Denied - virtual apps cannot run',
                valueColor: engine?.hiddenApiGranted == false ? UniqueColors.error : null,
              ),
              const Divider(),
              SectionRow(
                label: 'Native library',
                value: engine == null
                    ? 'Unknown'
                    : engine.nativeLoaded
                        ? 'Loaded'
                        : (engine.nativeLoadError ?? 'Not loaded'),
                valueColor: engine?.nativeLoaded == false ? UniqueColors.error : null,
              ),
              const Divider(),
              SectionRow(
                label: 'Memory page size',
                value: engine == null
                    ? 'Unknown'
                    : '${engine.pageSizeBytes ~/ 1024} KB'
                        '${engine.usesLargePages ? '  -  apps must ship 16 KB-aligned libraries' : ''}',
              ),
              const Divider(),
              SectionRow(
                label: 'Path redirection',
                value: (engine?.ioRedirectImplemented ?? false)
                    ? 'Active'
                    : 'Not active in this build',
              ),
            ],
          ),

          SectionCard(
            title: 'Google',
            children: [
              // Read from the device, not asserted. Everything in this section is a fact
              // about what is installed here; none of it is a claim that a flow works.
              SectionRow(
                label: 'Play services',
                value: _google == null
                    ? 'Reading...'
                    : !_google!.gmsPresent
                        ? 'Not installed on this device'
                        : _google!.presentButUnusable
                            ? 'Installed but not usable'
                                '${_google!.gmsEnabled ? "" : " - disabled"}'
                                '  (${_google!.gmsVersionName})'
                            : 'Available  -  ${_google!.gmsVersionName}',
                valueColor: _google?.presentButUnusable == true
                    ? UniqueColors.warning
                    : null,
              ),
              const Divider(),
              SectionRow(
                label: 'Play Store',
                value: _google == null
                    ? '-'
                    : _google!.vendingPresent ? 'Installed' : 'Not installed',
              ),
              const Divider(),
              SectionRow(
                label: 'Browser for OAuth',
                value: _google == null
                    ? '-'
                    : _google!.customTabsAvailable
                        ? _google!.customTabsPackage
                        : 'None - browser-based sign-in cannot run here',
                valueColor: _google?.customTabsAvailable == false
                    ? UniqueColors.warning
                    : null,
              ),
              const Divider(),
              SectionRow(
                label: 'Sign-in flows',
                value: _google?.bridgesImplemented == true
                    ? 'Available'
                    : 'Not implemented yet - routing is decided and recorded, but no '
                        'flow has an implementation',
                valueColor: UniqueColors.warning,
              ),
              const Divider(),
              SectionRow(
                label: 'Google diagnostics',
                onTap: () => _openDiagnostics(context, channel: 'GOOGLE'),
                trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              ),
            ],
          ),

          SectionCard(
            title: 'Advanced',
            children: [
              SectionRow(
                label: 'Device test',
                value: 'What this phone is, and the physical-device sequence — run and '
                    'recorded here, with no computer',
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => DeviceTestScreen(state: widget.state),
                  ),
                ),
                trailing: const Icon(Icons.phonelink_setup_rounded, size: 20),
              ),
              const Divider(),
              SectionRow(
                label: 'Diagnostics',
                value: '${widget.state.diagnostics.length} events recorded',
                onTap: () => _openDiagnostics(context),
                trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              ),
              const Divider(),
              SectionRow(
                label: 'Export diagnostics',
                value: _exporting
                    ? 'Collecting from every running app...'
                    : _exportSummary ??
                        'A zip with UNIQUE\'s logs and the device, and nothing from '
                            'inside a virtualized app',
                onTap: _exporting ? null : _export,
                trailing: _exporting
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.archive_outlined, size: 20),
              ),
            ],
          ),

          SectionCard(
            title: 'About',
            children: [
              SectionRow(label: 'Version', value: engine?.versionName ?? '-'),
              const Divider(),
              SectionRow(
                label: 'Android',
                value: engine == null
                    ? '-'
                    : 'API ${engine.sdkInt}  -  ${engine.abis.join(", ")}',
              ),
              const Divider(),
              SectionRow(
                label: 'Open-source licences',
                onTap: () => showLicensePage(
                  context: context,
                  applicationName: 'Unique',
                  applicationVersion: engine?.versionName ?? '',
                ),
                trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              ),
            ],
          ),
        ],
      ),
    );
  }

  bool _exporting = false;
  String? _exportSummary;
  GoogleStatus? _google;

  @override
  void initState() {
    super.initState();
    _loadGoogle();
  }

  /// Asked of the device every time this screen opens.
  ///
  /// Play services can be disabled, updated or side-loaded between two visits, and a
  /// cached "available" is precisely the answer that sends someone chasing a failure
  /// that is not theirs.
  Future<void> _loadGoogle() async {
    final status = await widget.state.googleStatus();
    if (!mounted) return;
    setState(() => _google = status);
  }

  /// Writes the package, then says what is in it.
  ///
  /// The counts are reported rather than a bare "done": an export taken with nothing
  /// running holds far less than one taken while the app that misbehaved is still up,
  /// and the person about to send it is the only one who can tell the difference.
  Future<void> _export() async {
    setState(() {
      _exporting = true;
      _exportSummary = null;
    });
    final result = await widget.state.exportDiagnostics();
    if (!mounted) return;
    setState(() {
      _exporting = false;
      _exportSummary = result.ok
          ? '${result.name}  -  ${_formatBytes(result.bytes)}, '
              '${result.lines} lines from ${result.processes + 1} '
              '${result.processes == 0 ? "process" : "processes"}'
          : result.message ?? 'Export failed';
    });
    if (!result.ok) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('Saved to ${result.path}')),
    );
  }

  static String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  void _openDiagnostics(BuildContext context, {String? channel}) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => DiagnosticsScreen(state: widget.state, channel: channel),
      ),
    );
  }
}

/// Structured, filterable diagnostics. Rendered from the event fields, never from a
/// pre-formatted string, so filtering stays exact.
class DiagnosticsScreen extends StatelessWidget {
  const DiagnosticsScreen({super.key, required this.state, this.channel});

  final AppState state;
  final String? channel;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final records = channel == null
        ? state.diagnostics
        : state.diagnostics.where((r) => r.channel == channel).toList();

    return Scaffold(
      appBar: AppBar(title: Text(channel == null ? 'Diagnostics' : 'Diagnostics - $channel')),
      body: records.isEmpty
          ? Center(
              child: Text('Nothing recorded yet', style: theme.textTheme.bodyMedium))
          : ListView.separated(
              padding: const EdgeInsets.all(UniqueSpace.lg),
              itemCount: records.length,
              separatorBuilder: (_, __) => const SizedBox(height: UniqueSpace.sm),
              itemBuilder: (context, i) => _DiagTile(record: records[i]),
            ),
    );
  }
}

class _DiagTile extends StatelessWidget {
  const _DiagTile({required this.record});

  final DiagRecord record;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = switch (record.level) {
      DiagLevel.error => UniqueColors.error,
      DiagLevel.warn => UniqueColors.warning,
      _ => theme.colorScheme.onSurface.withValues(alpha: 0.5),
    };
    final t = record.timestamp;
    final stamp = '${t.hour.toString().padLeft(2, '0')}:'
        '${t.minute.toString().padLeft(2, '0')}:'
        '${t.second.toString().padLeft(2, '0')}';

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(UniqueSpace.md),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(width: 6, height: 6,
                    decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
                const SizedBox(width: UniqueSpace.sm),
                Expanded(
                  child: Text(record.code,
                      style: theme.textTheme.titleMedium?.copyWith(fontSize: 14)),
                ),
                Text('${record.channel}  $stamp', style: theme.textTheme.labelSmall),
              ],
            ),
            if (record.fields.isNotEmpty) ...[
              const SizedBox(height: UniqueSpace.sm),
              for (final e in record.fields.entries)
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text('${e.key}: ${e.value}',
                      style: theme.textTheme.bodySmall?.copyWith(fontFamily: 'monospace')),
                ),
            ],
          ],
        ),
      ),
    );
  }
}
