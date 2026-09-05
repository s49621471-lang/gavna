import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/strings.dart';
import '../models/models.dart';
import '../state/app_state.dart';
import '../theme/unique_theme.dart';
import '../widgets/common.dart';

/// The physical-device test, run from the device.
///
/// Two halves that answer different questions. The **report** is what this phone is —
/// ABIs, page size, whether its Vulkan is a real GPU or a software rasteriser, which
/// WebView it ships, what Google stack it has. The **sequence** is what a person has to
/// try, in an order where a failure explains the ones after it.
///
/// Both exist because the automated suite cannot answer either. It runs on an emulator and
/// never looks at the screen; this runs on hardware and a person does. And both end up in
/// the same diagnostics package, so a result can be read next to the device it came from
/// without anyone needing `adb`, root, or a computer.
class DeviceTestScreen extends StatefulWidget {
  const DeviceTestScreen({super.key, required this.state});

  final AppState state;

  @override
  State<DeviceTestScreen> createState() => _DeviceTestScreenState();
}

class _DeviceTestScreenState extends State<DeviceTestScreen> {
  List<ReportSection>? _report;
  List<ChecklistStep>? _steps;
  bool _sharing = false;
  String? _shareSummary;
  bool _shareFailed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final report = await widget.state.deviceReport();
    final steps = await widget.state.checklist();
    if (!mounted) return;
    setState(() {
      _report = report;
      _steps = steps;
    });
  }

  Future<void> _share() async {
    setState(() {
      _sharing = true;
      _shareSummary = null;
      _shareFailed = false;
    });
    final result = await widget.state.shareDiagnostics();
    if (!mounted) return;
    setState(() {
      _sharing = false;
      _shareFailed = !result.ok;
      _shareSummary = result.ok
          ? '${result.name}  -  ${result.lines} lines from '
              '${result.processes + 1} processes'
          : result.message ?? 'Export failed';
    });
  }

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    final steps = _steps;
    final done = steps?.where((s) => s.done).length ?? 0;
    final total = steps?.length ?? 0;

    return Scaffold(
      appBar: AppBar(
        title: Text(s.t('devtest.title')),
        actions: [
          IconButton(
            tooltip: s.t('devtest.reread'),
            onPressed: _load,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(
            UniqueSpace.lg, 0, UniqueSpace.lg, UniqueSpace.xxl),
        children: [
          NoticeBanner(
            tone: NoticeTone.info,
            title: s.t('devtest.notice.title'),
            message: s.t('devtest.notice.body'),
          ),

          if (steps != null)
            SectionCard(
              title: s.t('devtest.sequence', {'done': done, 'total': total}),
              children: [
                for (final step in steps) ...[
                  _StepRow(
                    step: step,
                    onVerdict: (verdict) async {
                      final updated = await widget.state
                          .setChecklistStep(step.id, verdict, step.note);
                      if (mounted) setState(() => _steps = updated);
                    },
                    onNote: (note) async {
                      final updated = await widget.state
                          .setChecklistStep(step.id, step.verdict, note);
                      if (mounted) setState(() => _steps = updated);
                    },
                  ),
                  if (step != steps.last) const Divider(height: 1),
                ],
              ],
            ),

          SectionCard(
            title: s.t('devtest.send'),
            children: [
              SectionRow(
                label: s.t(_sharing ? 'devtest.collecting' : 'devtest.share'),
                value: _shareSummary ?? s.t('devtest.shareBody'),
                valueColor: _shareFailed ? UniqueColors.error : null,
                onTap: _sharing ? null : _share,
                trailing: _sharing
                    ? const SizedBox(
                        width: 16, height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.ios_share_rounded, size: 20),
              ),
              const Divider(height: 1),
              SectionRow(
                label: s.t('devtest.clear'),
                value: s.t('devtest.clearBody'),
                onTap: () async {
                  final updated = await widget.state.resetChecklist();
                  if (mounted) setState(() => _steps = updated);
                },
                trailing: const Icon(Icons.restart_alt_rounded, size: 20),
              ),
            ],
          ),

          if (_report == null)
            SectionCard(children: [SectionRow(label: s.t('devtest.readingDevice'))])
          else
            for (final section in _report!)
              SectionCard(
                title: section.title,
                children: [
                  for (final entry in section.values.entries) ...[
                    SectionRow(
                      label: entry.key,
                      value: entry.value,
                      monospaceValue: true,
                      onTap: () {
                        Clipboard.setData(
                            ClipboardData(text: '${entry.key}=${entry.value}'));
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(
                              content: Text(
                                  s.t('devtest.copied', {'key': entry.key}))),
                        );
                      },
                    ),
                    if (entry.key != section.values.keys.last)
                      const Divider(height: 1),
                  ],
                ],
              ),
        ],
      ),
    );
  }
}

/// One step: what to do, what it would tell you, and what happened.
///
/// Stateful for the note field alone. The note has to survive the tester moving to the
/// next step without being written to disk on every keystroke — a save per character
/// rewrites the whole checklist file, which is visible as jank on a slow phone and is the
/// kind of thing that only shows up on the device this screen exists for. So the text is
/// held here and committed when the field loses focus or the tester submits it.
class _StepRow extends StatefulWidget {
  const _StepRow({required this.step, required this.onVerdict, required this.onNote});

  final ChecklistStep step;
  final ValueChanged<StepVerdict> onVerdict;
  final ValueChanged<String> onNote;

  @override
  State<_StepRow> createState() => _StepRowState();
}

class _StepRowState extends State<_StepRow> {
  late final TextEditingController _note =
      TextEditingController(text: widget.step.note);
  late final FocusNode _focus = FocusNode()..addListener(_commitOnBlur);

  void _commitOnBlur() {
    if (_focus.hasFocus) return;
    if (_note.text == widget.step.note) return;
    widget.onNote(_note.text);
  }

  @override
  void dispose() {
    // Committed on the way out as well: leaving the screen with the keyboard up is the
    // ordinary way to finish typing, and it never fires a blur.
    if (_note.text != widget.step.note) widget.onNote(_note.text);
    _focus.removeListener(_commitOnBlur);
    _focus.dispose();
    _note.dispose();
    super.dispose();
  }

  ChecklistStep get step => widget.step;

  Color? _tone(BuildContext context) => switch (step.verdict) {
        StepVerdict.pass => UniqueColors.success,
        StepVerdict.fail => UniqueColors.error,
        StepVerdict.blocked => UniqueColors.warning,
        StepVerdict.skipped => Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.4),
        StepVerdict.notRun => null,
      };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(
          UniqueSpace.lg, UniqueSpace.md, UniqueSpace.lg, UniqueSpace.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 8, height: 8,
                margin: const EdgeInsets.only(top: 6, right: UniqueSpace.sm),
                decoration: BoxDecoration(
                  color: _tone(context) ??
                      theme.colorScheme.onSurface.withValues(alpha: 0.2),
                  shape: BoxShape.circle,
                ),
              ),
              Expanded(
                child: Text('${step.id.substring(1)}. ${step.title}',
                    style: theme.textTheme.bodyLarge),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Padding(
            padding: const EdgeInsets.only(left: 16),
            child: Text(step.what, style: theme.textTheme.bodySmall),
          ),
          const SizedBox(height: UniqueSpace.sm),
          Padding(
            padding: const EdgeInsets.only(left: 16),
            child: Wrap(
              spacing: UniqueSpace.sm,
              children: [
                for (final v in [
                  (StepVerdict.pass, 'devtest.pass'),
                  (StepVerdict.fail, 'devtest.fail'),
                  (StepVerdict.blocked, 'devtest.blocked'),
                  (StepVerdict.skipped, 'devtest.skip'),
                ])
                  ChoiceChip(
                    label: Text(Strings.of(context).t(v.$2)),
                    selected: step.verdict == v.$1,
                    // The note is committed alongside the verdict: tapping a chip is the
                    // other way a tester finishes typing, and it does not blur the field.
                    onSelected: (_) {
                      if (_note.text != step.note) widget.onNote(_note.text);
                      widget.onVerdict(v.$1);
                    },
                  ),
              ],
            ),
          ),
          const SizedBox(height: UniqueSpace.sm),
          Padding(
            padding: const EdgeInsets.only(left: 16),
            child: TextField(
              controller: _note,
              focusNode: _focus,
              minLines: 1,
              maxLines: 6,
              textInputAction: TextInputAction.newline,
              keyboardType: TextInputType.multiline,
              style: theme.textTheme.bodySmall,
              decoration: InputDecoration(
                isDense: true,
                labelText: Strings.of(context).t('devtest.note'),
                hintText: Strings.of(context).t('devtest.noteHint'),
              ),
              onSubmitted: widget.onNote,
            ),
          ),
        ],
      ),
    );
  }
}
