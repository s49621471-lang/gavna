import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/strings.dart';
import '../models/models.dart';
import '../state/app_state.dart';
import '../theme/unique_theme.dart';
import '../widgets/common.dart';

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

  /// The Settings-screen accesses UNIQUE holds. Null while they are being read.
  List<SpecialAccess>? _special;
  GoogleStatus? _google;
  List<GoogleRoute>? _routes;
  String? _busyGroup;

  /// Whether this instance's expansion files are in place, and why not when they are not.
  ///
  /// Read on every visit rather than cached with the instance: the answer changes the
  /// moment the user grants all-files access, and coming back to this screen is exactly
  /// what they do next.
  String? _assetOutcome;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final special = await state.specialAccess();
    final permissions = await state.instancePermissions(app.vuid);
    final google = await state.googleStatus();
    final routes = await state.googleRouting(app.vuid);
    final assets = await state.guestAssetStatus(app.vuid);
    if (!mounted) return;
    setState(() {
      _permissions = permissions;
      _special = special;
      _google = google;
      _routes = routes;
      _assetOutcome = assets['outcome'];
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
      final s = Strings.of(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          // The group's name is filled in here, in the reader's language: the engine
          // spells it in English, and its own sentence is the fallback, not the source.
          content: Text(result.describe(
            s,
            s.t('common.failed'),
            {'group': s.orElse('perm.${permission.group}', permission.label)},
          )),
          // Only when Android has stopped asking. Offering "Settings" for an ordinary
          // refusal would push the user somewhere they do not need to go.
          action: result.needsHostSettings
              ? SnackBarAction(
                  label: s.t('details.openSettings'),
                  onPressed: () => state.openHostSettings(),
                )
              : null,
          duration: result.needsHostSettings
              ? const Duration(seconds: 8)
              : const Duration(seconds: 4),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final s = Strings.of(context);
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
                            ? () => _run(context, () => state.launch(app),
                                s.t('details.launching'), state: state)
                            : null,
                        icon: const Icon(Icons.play_arrow_rounded),
                        label: Text(s.t('details.launch')),
                      ),
                    ),
                    const SizedBox(width: UniqueSpace.md),
                    IconButton.filledTonal(
                      onPressed: null,
                      icon: const Icon(Icons.stop_rounded),
                      tooltip: s.t('details.stop'),
                    ),
                  ],
                ),
                if (!launchable) ...[
                  const SizedBox(height: UniqueSpace.md),
                  NoticeBanner(
                    tone: NoticeTone.warning,
                    title: s.t('details.launchUnavailable'),
                    message: s.t('engine.degraded.body'),
                  ),
                ],
                // Stated before the launch, not after it. A game whose expansion files
                // are not there starts and shows an empty menu, and nothing about that
                // points at a Settings switch two screens away — so the switch is here,
                // on the screen with the Launch button, for as long as it is needed.
                if (_assetOutcome == 'SOURCE_UNREADABLE') ...[
                  const SizedBox(height: UniqueSpace.md),
                  NoticeBanner(
                    tone: NoticeTone.warning,
                    title: s.t('details.assetsBlocked'),
                    message: s.t('details.assetsBlockedBody'),
                    action: TextButton(
                      onPressed: () async {
                        await state.openSpecialAccess('allFiles');
                        await _load();
                      },
                      child: Text(s.t('launch.grantAllFiles')),
                    ),
                  ),
                ],

                SectionCard(
                  title: s.t('details.general'),
                  children: [
                    SectionRow(label: s.t('details.package'), value: app.packageName),
                    const Divider(),
                    SectionRow(label: s.t('details.versionCode'), value: '${app.versionCode}'),
                    const Divider(),
                    SectionRow(
                        label: s.t('details.instance'), value: app.profileLabel(s)),
                  ],
                ),

                SectionCard(
                  title: s.t('details.permissions'),
                  children: [
                    if (_permissions == null)
                      SectionRow(label: s.t('common.reading'), value: '')
                    else if (_permissions!.isEmpty)
                      SectionRow(
                        label: s.t('details.noPermissions'),
                        value: s.t('details.noPermissionsBody'),
                      )
                    else
                      // Only the groups this app's own manifest asks for. Offering Camera
                      // to an app that cannot use it is a lie about the app.
                      for (final p in _permissions!) ...[
                        SectionRow(
                          label: s.orElse('perm.${p.group}', p.label),
                          value: p.blockedByHost
                              ? s.t('details.hostMissing')
                              : p.granted
                                  ? s.t('details.allowed')
                                  : s.t('details.notAllowed'),
                          valueColor:
                              p.blockedByHost ? UniqueColors.warning : null,
                          trailing: _busyGroup == p.group
                              ? const SizedBox(
                                  width: 16,
                                  height: 16,
                                  child: CircularProgressIndicator(strokeWidth: 2))
                              // Enabled even when UNIQUE lacks the permission: turning it
                              // on asks Android for it first. A disabled switch here left
                              // the user reading "grant it to UNIQUE" with nothing to
                              // press and no screen named.
                              : Switch(
                                  value: p.granted,
                                  onChanged: (v) => _togglePermission(p, v),
                                ),
                        ),
                        if (p != _permissions!.last) const Divider(),
                      ],
                  ],
                ),

                // Held by UNIQUE, not by this copy — and the card says so before it
                // offers a button, because a switch that is secretly global is exactly
                // the kind of thing people find out about afterwards.
                SectionCard(
                  title: s.t('details.specialAccess'),
                  children: [
                    SectionRow(
                      label: s.t('details.specialAccessBody'),
                      value: '',
                    ),
                    for (final access in _special ?? const <SpecialAccess>[]) ...[
                      const Divider(),
                      SectionRow(
                        label: s.t('access.${access.id}'),
                        value: access.granted
                            ? s.t('details.allowed')
                            : s.t('access.notGranted'),
                        valueColor:
                            access.granted ? null : UniqueColors.warning,
                        onTap: () => _openAccess(access),
                        trailing: access.granted
                            ? const Icon(Icons.check_rounded, size: 20)
                            : const Icon(Icons.open_in_new_rounded, size: 18),
                      ),
                    ],
                  ],
                ),

                SectionCard(
                  title: s.t('details.storage'),
                  children: [
                    SectionRow(label: s.t('details.data'), value: formatBytes(app.dataBytes)),
                    const Divider(),
                    SectionRow(label: s.t('details.cache'), value: formatBytes(app.cacheBytes)),
                    const Divider(),
                    SectionRow(label: s.t('details.external'), value: formatBytes(app.externalBytes)),
                    const Divider(),
                    SectionRow(
                      label: s.t('details.clearCache'),
                      onTap: () => _run(context, () => state.clearCache(app),
                          s.t('details.cacheCleared')),
                      trailing: const Icon(Icons.chevron_right_rounded, size: 20),
                    ),
                    const Divider(),
                    SectionRow(
                      label: s.t('details.clearData'),
                      value: s.t('details.clearDataBody'),
                      valueColor: UniqueColors.warning,
                      onTap: () => _confirmClearData(context),
                      trailing: const Icon(Icons.chevron_right_rounded, size: 20),
                    ),
                  ],
                ),

                SectionCard(
                  title: s.t('details.deviceProfile'),
                  children: [
                    SectionRow(
                      label: s.t('details.androidId'),
                      value: app.androidId,
                      monospaceValue: true,
                      trailing: IconButton(
                        tooltip: s.t('common.copy'),
                        icon: const Icon(Icons.copy_rounded, size: 18),
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: app.androidId));
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                                content: Text(s.t('details.androidIdCopied'))),
                          );
                        },
                      ),
                    ),
                    const Divider(),
                    const Divider(),
                    SectionRow(
                      label: s.t('details.instanceId'),
                      value: app.instanceId,
                      monospaceValue: true,
                    ),
                    const Divider(),
                    SectionRow(
                      label: s.t('details.generation'),
                      value: '${app.generation}',
                    ),
                    const Divider(),
                    SectionRow(
                      label: s.t('details.regenerate'),
                      value: s.t('details.regenerateBody'),
                      trailing: const Icon(Icons.refresh_rounded, size: 20),
                    ),
                  ],
                ),

                SectionCard(
                  title: s.t('details.google'),
                  children: [
                    // Read from the device. Nothing here claims a flow works, because
                    // none of them is implemented yet and saying otherwise would send a
                    // user chasing a failure that is not theirs.
                    SectionRow(
                      label: s.t('details.googlePresent'),
                      value: _google == null
                          ? s.t('common.reading')
                          : !_google!.gmsPresent
                              ? s.t('settings.notInstalled')
                              : _google!.presentButUnusable
                                  ? s.t('settings.presentUnusable')
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
                    SectionRow(
                      label: s.t('settings.signInFlows'),
                      value: s.t('details.googleFlows'),
                      trailing: const _StatusDot(tone: NoticeTone.warning),
                    ),
                    // The router's real decisions for this app's own manifest, not a
                    // fixed list. Two apps get different answers, which is the point.
                    if (_routes == null)
                      SectionRow(label: s.t('common.reading'), value: '')
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

              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Opens the Settings screen that grants one access, then re-reads the answer.
  ///
  /// Re-read on return rather than assumed: the user may have opened the screen and come
  /// straight back, and a row that says "granted" because a button was pressed is the
  /// same lie as a switch that does nothing.
  Future<void> _openAccess(SpecialAccess access) async {
    final messenger = ScaffoldMessenger.of(context);
    final s = Strings.of(context);
    final failed = s.t('common.failed');
    final result = await state.openSpecialAccess(access.id);
    if (!result.ok) {
      messenger.showSnackBar(SnackBar(content: Text(result.describe(s, failed))));
      return;
    }
    final refreshed = await state.specialAccess();
    if (!mounted) return;
    setState(() => _special = refreshed);
  }

  /// Runs an engine action and reports its real outcome. A failure is shown with the
  /// engine's own message rather than a generic one - the engine knows why.
  static Future<void> _run(
    BuildContext context,
    Future<EngineOutcome> Function() action,
    String successMessage, {
    AppState? state,
  }) async {
    // Both are read before the await: the context may be gone by the time the engine
    // answers, and a snackbar is not worth holding one across that gap. `state` is passed
    // in for the same reason — this is static, so there is no widget to read it from.
    final messenger = ScaffoldMessenger.of(context);
    final s = Strings.of(context);
    final failed = s.t('common.failed');
    final result = await action();
    if (result.ok && result.warning == 'ASSETS_UNREADABLE') {
      // A launch that worked and a game that will look broken. The switch that fixes it
      // is two screens away and nothing about an empty menu points at it, so the snackbar
      // carries the way there rather than only the news.
      messenger.showSnackBar(SnackBar(
        content: Text(s.t('launch.assetsUnreadable')),
        duration: const Duration(seconds: 10),
        action: state == null
            ? null
            : SnackBarAction(
                label: s.t('launch.grantAllFiles'),
                onPressed: () => state.openSpecialAccess('allFiles'),
              ),
      ));
      return;
    }
    messenger.showSnackBar(SnackBar(
      content: Text(result.ok ? successMessage : result.describe(s, failed)),
    ));
  }

  void _confirmClearData(BuildContext context) {
    final s = Strings.of(context);
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(s.t('details.clearDataTitle', {'app': app.label})),
        content:
            Text(s.t('details.clearDataConfirm', {'profile': app.profileLabel(s)})),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: Text(s.t('common.cancel')),
          ),
          FilledButton(
            onPressed: () {
              Navigator.pop(dialogContext);
              _run(context, () => state.clearData(app), s.t('details.dataCleared'));
            },
            child: Text(s.t('details.clearDataAction')),
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
