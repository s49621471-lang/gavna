import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// UNIQUE's palette.
///
/// Dark-first and deliberately narrow: one accent, one background, three surface
/// elevations. A utility application earns its "premium" feel from restraint and
/// typography, not from colour count - so semantic colours (success/warning/error) are
/// the only others allowed, and they appear only where they carry meaning.
class UniqueColors {
  const UniqueColors._();

  // Ground and surfaces. Near-black rather than pure black: pure black makes elevation
  // impossible to read on OLED and makes every edge look like a seam.
  static const background = Color(0xFF0B0B0D);
  static const surface = Color(0xFF141417);
  static const surfaceHigh = Color(0xFF1C1C21);
  static const surfaceHighest = Color(0xFF24242A);
  static const outline = Color(0xFF2E2E36);

  static const accent = Color(0xFF6E8BFF);
  static const accentMuted = Color(0xFF3D4B8A);
  static const onAccent = Color(0xFF07091A);

  static const textPrimary = Color(0xFFF2F3F7);
  static const textSecondary = Color(0xFF9C9CA8);
  static const textTertiary = Color(0xFF6A6A76);

  static const success = Color(0xFF5FD08A);
  static const warning = Color(0xFFE8B75C);
  static const error = Color(0xFFFF6B6B);

  // Light ground, for the rare user who runs a light system theme. Same structure so
  // every widget reads tokens rather than branching on brightness.
  static const lightBackground = Color(0xFFF7F7FA);
  static const lightSurface = Color(0xFFFFFFFF);
  static const lightSurfaceHigh = Color(0xFFF0F0F5);
  static const lightOutline = Color(0xFFDCDCE4);
  static const lightTextPrimary = Color(0xFF14141A);
  static const lightTextSecondary = Color(0xFF5A5A66);
}

/// Motion. One place, so nothing in the app invents its own timing.
///
/// 150-260 ms with an emphasised easing: fast enough that the interface never feels like
/// it is waiting on itself, slow enough to read as deliberate.
class UniqueMotion {
  const UniqueMotion._();

  static const fast = Duration(milliseconds: 150);
  static const medium = Duration(milliseconds: 220);
  static const slow = Duration(milliseconds: 300);

  static const emphasized = Cubic(0.2, 0.0, 0.0, 1.0);
  static const standard = Cubic(0.2, 0.0, 0.2, 1.0);
}

/// Spacing and radii, on a 4 dp grid.
class UniqueSpace {
  const UniqueSpace._();

  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 12.0;
  static const lg = 16.0;
  static const xl = 24.0;
  static const xxl = 32.0;

  static const radiusSm = 10.0;
  static const radiusMd = 16.0;
  static const radiusLg = 22.0;
  static const radiusFull = 999.0;
}

class UniqueTheme {
  const UniqueTheme._();

  /// Builds the theme.
  ///
  /// [dynamicSeed] is the wallpaper accent read from the platform when Material You is
  /// enabled; null falls back to UNIQUE's own accent. Reading it through the platform
  /// rather than a package keeps the dependency list at zero for one colour.
  static ThemeData dark({Color? dynamicSeed}) {
    final accent = dynamicSeed ?? UniqueColors.accent;
    final scheme = ColorScheme.fromSeed(
      seedColor: accent,
      brightness: Brightness.dark,
    ).copyWith(
      primary: accent,
      surface: UniqueColors.surface,
      onSurface: UniqueColors.textPrimary,
      surfaceContainerHighest: UniqueColors.surfaceHighest,
      outline: UniqueColors.outline,
      error: UniqueColors.error,
    );
    return _build(scheme, UniqueColors.background, UniqueColors.textPrimary,
        UniqueColors.textSecondary, Brightness.dark);
  }

  static ThemeData light({Color? dynamicSeed}) {
    final accent = dynamicSeed ?? UniqueColors.accent;
    final scheme = ColorScheme.fromSeed(
      seedColor: accent,
      brightness: Brightness.light,
    ).copyWith(
      primary: accent,
      surface: UniqueColors.lightSurface,
      onSurface: UniqueColors.lightTextPrimary,
      outline: UniqueColors.lightOutline,
    );
    return _build(scheme, UniqueColors.lightBackground, UniqueColors.lightTextPrimary,
        UniqueColors.lightTextSecondary, Brightness.light);
  }

  static ThemeData _build(ColorScheme scheme, Color background, Color textPrimary,
      Color textSecondary, Brightness brightness) {
    final base = ThemeData(colorScheme: scheme, useMaterial3: true);
    return base.copyWith(
      scaffoldBackgroundColor: background,
      canvasColor: background,
      splashFactory: InkSparkle.splashFactory,
      textTheme: _textTheme(base.textTheme, textPrimary, textSecondary),
      appBarTheme: AppBarTheme(
        backgroundColor: background,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        systemOverlayStyle: brightness == Brightness.dark
            ? SystemUiOverlayStyle.light
            : SystemUiOverlayStyle.dark,
        titleTextStyle: TextStyle(
          color: textPrimary,
          fontSize: 22,
          fontWeight: FontWeight.w600,
          letterSpacing: -0.4,
        ),
      ),
      cardTheme: CardThemeData(
        color: scheme.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(UniqueSpace.radiusMd),
        ),
      ),
      dividerTheme: DividerThemeData(
        color: scheme.outline.withValues(alpha: 0.6),
        space: 1,
        thickness: 1,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? scheme.onPrimary : textSecondary,
        ),
        trackColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected)
              ? scheme.primary
              : scheme.surfaceContainerHighest,
        ),
        trackOutlineColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? Colors.transparent : scheme.outline,
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(0, 44),
          padding: const EdgeInsets.symmetric(horizontal: UniqueSpace.xl),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(UniqueSpace.radiusFull),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: scheme.primary,
          textStyle: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surface,
        contentPadding: const EdgeInsets.symmetric(
            horizontal: UniqueSpace.lg, vertical: UniqueSpace.md),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(UniqueSpace.radiusFull),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(UniqueSpace.radiusFull),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(UniqueSpace.radiusFull),
          borderSide: BorderSide(color: scheme.primary, width: 1.5),
        ),
        hintStyle: TextStyle(color: textSecondary, fontSize: 15),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: scheme.surfaceContainerHighest,
        contentTextStyle: TextStyle(color: textPrimary, fontSize: 14),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(UniqueSpace.radiusMd),
        ),
      ),
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: _SharedAxisTransitionBuilder(),
        },
      ),
    );
  }

  static TextTheme _textTheme(TextTheme base, Color primary, Color secondary) => base.copyWith(
        headlineSmall: base.headlineSmall?.copyWith(
            color: primary, fontWeight: FontWeight.w600, letterSpacing: -0.5),
        titleLarge: base.titleLarge
            ?.copyWith(color: primary, fontWeight: FontWeight.w600, letterSpacing: -0.3),
        titleMedium: base.titleMedium
            ?.copyWith(color: primary, fontWeight: FontWeight.w600, fontSize: 15),
        bodyLarge: base.bodyLarge?.copyWith(color: primary, fontSize: 15),
        bodyMedium: base.bodyMedium?.copyWith(color: secondary, fontSize: 14, height: 1.4),
        bodySmall: base.bodySmall?.copyWith(color: secondary, fontSize: 12.5),
        labelLarge: base.labelLarge?.copyWith(color: primary, fontWeight: FontWeight.w600),
        labelSmall: base.labelSmall?.copyWith(
            color: secondary, fontSize: 11, letterSpacing: 0.4, fontWeight: FontWeight.w600),
      );
}

/// Shared-axis page transition: a short horizontal slide combined with a fade.
///
/// Material's default Android transition on this Flutter version is a vertical slide,
/// which reads as heavier than it should for a utility app moving between peer screens.
class _SharedAxisTransitionBuilder extends PageTransitionsBuilder {
  const _SharedAxisTransitionBuilder();

  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    final curved = CurvedAnimation(
      parent: animation,
      curve: UniqueMotion.emphasized,
      reverseCurve: UniqueMotion.emphasized.flipped,
    );
    return FadeTransition(
      opacity: curved,
      child: SlideTransition(
        position: Tween<Offset>(
          begin: const Offset(0.06, 0),
          end: Offset.zero,
        ).animate(curved),
        child: child,
      ),
    );
  }
}
