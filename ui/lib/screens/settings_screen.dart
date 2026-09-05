import 'package:flutter/material.dart';

import '../l10n/strings.dart';
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
    final s = Strings.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(s.t('settings.title'))),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(
            UniqueSpace.lg, 0, UniqueSpace.lg, UniqueSpace.xxl),
        children: [
          SectionCard(
            title: s.t('settings.appearance'),
            children: [
              SectionRow(
                label: s.t('settings.language'),
                value: widget.state.language == AppLanguage.system
                    ? '${s.t('settings.languageBody')}  ·  ${AppLanguage.system.nativeName}'
                    : widget.state.language.nativeName,
                onTap: _pickLanguage,
                trailing: const Icon(Icons.translate_rounded, size: 20),
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.dynamicColor'),
                value: s.t('settings.dynamicColorBody'),
                trailing: Switch(
                  value: widget.state.dynamicColor,
                  onChanged: (v) => setState(() => widget.state.dynamicColor = v),
                ),
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.reduceMotion'),
                value: s.t('settings.reduceMotionBody'),
                trailing: Switch(
                  value: widget.state.reducedMotion,
                  onChanged: (v) => setState(() => widget.state.reducedMotion = v),
                ),
              ),
            ],
          ),

          SectionCard(
            title: s.t('settings.engine'),
            children: [
              SectionRow(
                label: s.t('settings.platformAccess'),
                value: engine == null
                    ? s.t('common.unknown')
                    : engine.hiddenApiGranted
                        ? s.t('settings.granted')
                        : s.t('settings.denied'),
                valueColor: engine?.hiddenApiGranted == false ? UniqueColors.error : null,
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.nativeLibrary'),
                value: engine == null
                    ? s.t('common.unknown')
                    : engine.nativeLoaded
                        ? s.t('settings.loaded')
                        : (engine.nativeLoadError ?? s.t('settings.notLoaded')),
                valueColor: engine?.nativeLoaded == false ? UniqueColors.error : null,
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.pageSize'),
                value: engine == null
                    ? s.t('common.unknown')
                    : '${engine.pageSizeBytes ~/ 1024} KB'
                        '${engine.usesLargePages ? '  —  ${s.t('settings.largePages')}' : ''}',
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.pathRedirection'),
                value: (engine?.ioRedirectImplemented ?? false)
                    ? s.t('settings.active')
                    : s.t('settings.notActive'),
              ),
            ],
          ),

          SectionCard(
            title: s.t('settings.google'),
            children: [
              // Read from the device, not asserted. Everything in this section is a fact
              // about what is installed here; none of it is a claim that a flow works.
              SectionRow(
                label: s.t('settings.playServices'),
                value: _google == null
                    ? s.t('common.reading')
                    : !_google!.gmsPresent
                        ? s.t('settings.notInstalledHere')
                        : _google!.presentButUnusable
                            ? '${s.t('settings.presentUnusable')}'
                                '${_google!.gmsEnabled ? "" : " — ${s.t('settings.disabledSuffix')}"}'
                                '  (${_google!.gmsVersionName})'
                            : '${s.t('settings.available')}  —  ${_google!.gmsVersionName}',
                valueColor: _google?.presentButUnusable == true
                    ? UniqueColors.warning
                    : null,
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.playStore'),
                value: _google == null
                    ? s.t('common.none')
                    : _google!.vendingPresent
                        ? s.t('settings.installed')
                        : s.t('settings.notInstalled'),
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.oauthBrowser'),
                value: _google == null
                    ? s.t('common.none')
                    : _google!.customTabsAvailable
                        ? _google!.customTabsPackage
                        : s.t('settings.noBrowser'),
                valueColor: _google?.customTabsAvailable == false
                    ? UniqueColors.warning
                    : null,
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.signInFlows'),
                value: _google?.bridgesImplemented == true
                    ? s.t('settings.available')
                    : s.t('settings.signInNotImplemented'),
                valueColor: UniqueColors.warning,
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.googleDiagnostics'),
                onTap: () => _openDiagnostics(context, channel: 'GOOGLE'),
                trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              ),
            ],
          ),

          SectionCard(
            title: s.t('settings.advanced'),
            children: [
              SectionRow(
                label: s.t('settings.deviceTest'),
                value: s.t('settings.deviceTestBody'),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => DeviceTestScreen(state: widget.state),
                  ),
                ),
                trailing: const Icon(Icons.phonelink_setup_rounded, size: 20),
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.diagnostics'),
                value: s.t('settings.diagnosticsBody',
                    {'count': widget.state.diagnostics.length}),
                onTap: () => _openDiagnostics(context),
                trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.export'),
                value: _exporting
                    ? s.t('settings.exporting')
                    : _exportSummary ?? s.t('settings.exportBody'),
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
            title: s.t('settings.about'),
            children: [
              SectionRow(
                  label: s.t('settings.version'),
                  value: engine?.versionName ?? s.t('common.none')),
              const Divider(),
              SectionRow(
                label: s.t('settings.android'),
                value: engine == null
                    ? s.t('common.none')
                    : 'API ${engine.sdkInt}  —  ${engine.abis.join(", ")}',
              ),
              const Divider(),
              SectionRow(
                label: s.t('settings.licences'),
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

  /// The language picker.
  ///
  /// Each option is named in its own language, never translated into the current one — a
  /// picker that renames "Русский" to "Russian" is unreadable to exactly the person who
  /// needs it. `System` is first because it is the default and the right answer for most
  /// people; it follows the phone and falls back to English for a language UNIQUE does
  /// not have.
  Future<void> _pickLanguage() async {
    final s = Strings.of(context);
    final chosen = await showModalBottomSheet<AppLanguage>(
      context: context,
      showDragHandle: true,
      backgroundColor: Theme.of(context).colorScheme.surface,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(
                  UniqueSpace.lg, 0, UniqueSpace.lg, UniqueSpace.sm),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(s.t('settings.language'),
                    style: Theme.of(context).textTheme.titleMedium),
              ),
            ),
            for (final language in AppLanguage.values)
              ListTile(
                title: Text(language.nativeName),
                trailing: widget.state.language == language
                    ? const Icon(Icons.check_rounded, size: 20)
                    : null,
                onTap: () => Navigator.pop(context, language),
              ),
            const SizedBox(height: UniqueSpace.sm),
          ],
        ),
      ),
    );
    if (chosen == null || !mounted) return;
    setState(() => widget.state.language = chosen);
  }

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
    final s = Strings.of(context);
    setState(() {
      _exporting = false;
      _exportSummary = result.ok
          ? s.t('settings.exportSummary', {
              'name': result.name,
              'size': _formatBytes(result.bytes),
              'lines': result.lines,
              'procs': result.processes + 1,
              'word': s.t(result.processes == 0
                  ? 'settings.processes.one'
                  : 'settings.processes.many'),
            })
          : result.message ?? s.t('settings.exportFailed');
    });
    if (!result.ok) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(s.t('settings.savedTo', {'path': result.path}))),
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
    final s = Strings.of(context);
    final records = channel == null
        ? state.diagnostics
        : state.diagnostics.where((r) => r.channel == channel).toList();
    final title = s.t('settings.diagnostics');

    return Scaffold(
      appBar: AppBar(title: Text(channel == null ? title : '$title — $channel')),
      body: records.isEmpty
          ? Center(
              child: Text(s.t('settings.nothingRecorded'),
                  style: theme.textTheme.bodyMedium))
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
