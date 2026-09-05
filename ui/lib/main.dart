import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'l10n/strings.dart';
import 'screens/home_screen.dart';
import 'state/app_state.dart';
import 'theme/unique_theme.dart';
import 'widgets/common.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  runApp(const UniqueApp());
}

class UniqueApp extends StatefulWidget {
  const UniqueApp({super.key});

  @override
  State<UniqueApp> createState() => _UniqueAppState();
}

class _UniqueAppState extends State<UniqueApp> {
  final _state = AppState();

  @override
  void initState() {
    super.initState();
    _state.load();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _state,
      builder: (context, _) {
        // Material You: the wallpaper accent arrives through the platform's own dynamic
        // scheme when the device provides one. No package needed for a single colour.
        final seed = _state.dynamicColor ? null : UniqueColors.accent;
        return MaterialApp(
          title: 'Unique',
          debugShowCheckedModeBanner: false,
          themeMode: ThemeMode.dark,
          theme: UniqueTheme.light(dynamicSeed: seed),
          darkTheme: UniqueTheme.dark(dynamicSeed: seed),
          // Null means "follow the phone", which is the default and the right one: the
          // device's own language is the best first guess, and the picker in Settings is
          // for the times it is not.
          locale: _state.language.locale,
          supportedLocales: Strings.supportedLocales,
          localizationsDelegates: const [
            Strings.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          home: _Root(state: _state),
        );
      },
    );
  }
}

/// Decides between the brief startup state and Home.
///
/// There is no decorative splash: the Android window already shows the mark until
/// Flutter's first frame, and adding another one would be time the user waits for
/// nothing. This shows a progress state only if the engine query is genuinely slow.
class _Root extends StatelessWidget {
  const _Root({required this.state});

  final AppState state;

  @override
  Widget build(BuildContext context) {
    return AnimatedSwitcher(
      duration: UniqueMotion.medium,
      switchInCurve: UniqueMotion.emphasized,
      child: switch (state.status) {
        LoadState.idle || LoadState.loading => const _Starting(),
        LoadState.failed => _StartupFailed(state: state),
        LoadState.ready => HomeScreen(key: const ValueKey('home'), state: state),
      },
    );
  }
}

class _Starting extends StatelessWidget {
  const _Starting();

  @override
  Widget build(BuildContext context) => const Scaffold(
        key: ValueKey('starting'),
        body: Center(child: UniqueMark(size: 56)),
      );
}

class _StartupFailed extends StatelessWidget {
  const _StartupFailed({required this.state});

  final AppState state;

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    return Scaffold(
        key: const ValueKey('failed'),
        body: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(UniqueSpace.lg),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                NoticeBanner(
                  tone: NoticeTone.error,
                  title: s.t('startup.failed.title'),
                  message: state.error ?? s.t('startup.failed.body'),
                  action: FilledButton(
                    onPressed: state.load,
                    child: Text(s.t('common.tryAgain')),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
  }
}
