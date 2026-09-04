import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../theme/unique_theme.dart';

/// The UNIQUE mark, drawn rather than shipped as an asset so it scales cleanly and
/// tracks the accent colour. Geometry matches docs/brand/unique-mark.svg exactly.
class UniqueMark extends StatelessWidget {
  const UniqueMark({super.key, this.size = 28, this.showGhost = true});

  final double size;
  final bool showGhost;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SizedBox(
      width: size,
      height: size,
      child: CustomPaint(
        painter: _MarkPainter(
          mark: scheme.onSurface,
          ghost: scheme.primary,
          showGhost: showGhost,
        ),
      ),
    );
  }
}

class _MarkPainter extends CustomPainter {
  const _MarkPainter({required this.mark, required this.ghost, required this.showGhost});

  final Color mark;
  final Color ghost;
  final bool showGhost;

  // The glyph, in the 108x108 space the icon is authored in.
  Path _glyph() => Path()
    ..moveTo(34, 36)
    ..lineTo(34, 54)
    ..cubicTo(34, 65.05, 42.95, 74, 54, 74)
    ..cubicTo(65.05, 74, 74, 65.05, 74, 54)
    ..lineTo(74, 36)
    ..lineTo(65, 36)
    ..lineTo(65, 54)
    ..cubicTo(65, 60.08, 60.08, 65, 54, 65)
    ..cubicTo(47.92, 65, 43, 60.08, 43, 54)
    ..lineTo(43, 36)
    ..close();

  @override
  void paint(Canvas canvas, Size size) {
    final scale = size.width / 108.0;
    canvas.scale(scale);
    final glyph = _glyph();

    if (showGhost) {
      canvas.save();
      canvas.translate(-4, -4);
      canvas.drawPath(glyph, Paint()..color = ghost.withValues(alpha: 0.62));
      canvas.restore();
    }
    canvas.save();
    canvas.translate(showGhost ? 4 : 0, showGhost ? 4 : 0);
    canvas.drawPath(glyph, Paint()..color = mark);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_MarkPainter old) =>
      old.mark != mark || old.ghost != ghost || old.showGhost != showGhost;
}

/// A rounded app icon, falling back to a generated monogram tile when the platform has
/// no icon for the package. Never shows a broken-image box.
class AppIconTile extends StatelessWidget {
  const AppIconTile({
    super.key,
    required this.label,
    this.bytes,
    this.size = 48,
  });

  final String label;
  final Uint8List? bytes;
  final double size;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final radius = BorderRadius.circular(size * 0.28);
    if (bytes != null) {
      return ClipRRect(
        borderRadius: radius,
        child: Image.memory(bytes!, width: size, height: size, filterQuality: FilterQuality.medium),
      );
    }
    final initial = label.trim().isEmpty ? '?' : label.trim().characters.first.toUpperCase();
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        borderRadius: radius,
        color: scheme.surfaceContainerHighest,
      ),
      alignment: Alignment.center,
      child: Text(
        initial,
        style: TextStyle(
          color: scheme.onSurface.withValues(alpha: 0.7),
          fontSize: size * 0.4,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

/// A compact section container used throughout App Details and Settings.
class SectionCard extends StatelessWidget {
  const SectionCard({super.key, required this.children, this.title});

  final String? title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (title != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(
                UniqueSpace.xs, UniqueSpace.lg, UniqueSpace.xs, UniqueSpace.sm),
            child: Text(title!.toUpperCase(), style: theme.textTheme.labelSmall),
          ),
        Card(child: Column(children: children)),
      ],
    );
  }
}

/// One row inside a [SectionCard].
class SectionRow extends StatelessWidget {
  const SectionRow({
    super.key,
    required this.label,
    this.value,
    this.trailing,
    this.onTap,
    this.monospaceValue = false,
    this.valueColor,
  });

  final String label;
  final String? value;
  final Widget? trailing;
  final VoidCallback? onTap;
  final bool monospaceValue;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(UniqueSpace.radiusMd),
      child: Padding(
        padding: const EdgeInsets.symmetric(
            horizontal: UniqueSpace.lg, vertical: UniqueSpace.md),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(label, style: theme.textTheme.bodyLarge),
                  if (value != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      value!,
                      style: theme.textTheme.bodySmall?.copyWith(
                        fontFamily: monospaceValue ? 'monospace' : null,
                        color: valueColor,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            if (trailing != null) ...[const SizedBox(width: UniqueSpace.md), trailing!],
          ],
        ),
      ),
    );
  }
}

/// An inline notice. Used for engine-state messages that must not be mistaken for
/// decoration - the whole point is that the user learns what does not work.
class NoticeBanner extends StatelessWidget {
  const NoticeBanner({
    super.key,
    required this.title,
    required this.message,
    this.tone = NoticeTone.info,
    this.action,
  });

  final String title;
  final String message;
  final NoticeTone tone;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = switch (tone) {
      NoticeTone.info => theme.colorScheme.primary,
      NoticeTone.warning => UniqueColors.warning,
      NoticeTone.error => UniqueColors.error,
    };
    final icon = switch (tone) {
      NoticeTone.info => Icons.info_outline_rounded,
      NoticeTone.warning => Icons.warning_amber_rounded,
      NoticeTone.error => Icons.error_outline_rounded,
    };
    return Container(
      padding: const EdgeInsets.all(UniqueSpace.lg),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(UniqueSpace.radiusMd),
        border: Border.all(color: color.withValues(alpha: 0.28)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: color),
          const SizedBox(width: UniqueSpace.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: theme.textTheme.titleMedium?.copyWith(color: color)),
                const SizedBox(height: 4),
                Text(message, style: theme.textTheme.bodyMedium),
                if (action != null) ...[
                  const SizedBox(height: UniqueSpace.sm),
                  action!,
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

enum NoticeTone { info, warning, error }

String formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  const units = ['KB', 'MB', 'GB'];
  var value = bytes / 1024;
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return '${value.toStringAsFixed(value >= 10 ? 0 : 1)} ${units[unit]}';
}
