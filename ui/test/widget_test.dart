import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:unique_ui/models/models.dart';
import 'package:unique_ui/theme/unique_theme.dart';
import 'package:unique_ui/widgets/common.dart';
import 'package:unique_ui/l10n/strings.dart';

void main() {
  _localisationTests();
  group('formatBytes', () {
    test('renders human sizes with one decimal below ten', () {
      expect(formatBytes(0), '0 B');
      expect(formatBytes(512), '512 B');
      expect(formatBytes(1536), '1.5 KB');
      expect(formatBytes(20 * 1024), '20 KB');
      expect(formatBytes(5 * 1024 * 1024), '5.0 MB');
      expect(formatBytes(3 * 1024 * 1024 * 1024), '3.0 GB');
    });
  });

  group('EngineStatus', () {
    test('is not ready while any prerequisite is missing', () {
      EngineStatus build({
        bool native = true,
        bool hidden = true,
        bool launch = true,
      }) =>
          EngineStatus.fromMap({
            'versionName': '0.1.0',
            'sdkInt': 35,
            'abis': ['arm64-v8a'],
            'is64BitOnly': true,
            'nativeLoaded': native,
            'nativeLoadError': null,
            'pageSizeBytes': 16384,
            'hiddenApiGranted': hidden,
            'hiddenApiDetail': null,
            'virtualLaunchImplemented': launch,
            'ioRedirectImplemented': false,
            'settingsInterceptionImplemented': false,
          });

      expect(build().ready, isTrue);
      expect(build(native: false).ready, isFalse);
      expect(build(hidden: false).ready, isFalse);
      expect(build(launch: false).ready, isFalse);
    });

    test('reads arm64 support and large pages', () {
      final s = EngineStatus.fromMap({
        'abis': ['arm64-v8a'],
        'pageSizeBytes': 16384,
      });
      expect(s.supportsArm64, isTrue);
      expect(s.usesLargePages, isTrue);
    });

    test('missing fields degrade to a not-ready engine rather than throwing', () {
      final s = EngineStatus.fromMap(const {});
      expect(s.ready, isFalse);
      expect(s.supportsArm64, isFalse);
      expect(s.pageSizeBytes, 4096);
    });
  });

  group('InstalledApp', () {
    test('explains why an app cannot be added', () {
      final blocked = InstalledApp.fromMap({
        'package': 'com.example.app',
        'label': 'Example',
        'hasArm64': false,
      });
      // A key, not a sentence — and one both languages actually carry, which is the
      // half a `contains('arm64-v8a')` on English prose never checked.
      expect(blocked.blockedKey, 'add.blocked.noArm64');
      expect(Strings.missingKeys(), isEmpty);
      expect(
        const Strings(Locale('ru')).t(blocked.blockedKey!),
        isNot(blocked.blockedKey),
      );

      final ok = InstalledApp.fromMap({
        'package': 'com.example.app',
        'label': 'Example',
        'hasArm64': true,
      });
      expect(ok.blockedKey, isNull);
    });
  });

  testWidgets('the mark renders at any size without overflowing', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: UniqueTheme.dark(),
      home: const Scaffold(
        body: Center(child: UniqueMark(size: 96)),
      ),
    ));
    expect(find.byType(UniqueMark), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('an app tile falls back to a monogram when there is no icon',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: UniqueTheme.dark(),
      home: const Scaffold(
        body: Center(child: AppIconTile(label: 'Telegram')),
      ),
    ));
    expect(find.text('T'), findsOneWidget);
  });

  testWidgets('a notice banner shows its message', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: UniqueTheme.dark(),
      home: const Scaffold(
        body: NoticeBanner(
          tone: NoticeTone.warning,
          title: 'Launch unavailable',
          message: 'Running virtual apps is part of the next milestone.',
        ),
      ),
    ));
    expect(find.text('Launch unavailable'), findsOneWidget);
    expect(find.textContaining('next milestone'), findsOneWidget);
  });
  group('GoogleStatus', () {
    // The engine's Report.toMap() is a Map<String, String> because the same map is
    // written into Diagnostics and the device report; the bridge then adds one genuine
    // bool. Parsing has to survive both, and on a real device it did not: every open of
    // App Details threw "type 'String' is not a subtype of type 'bool?' in type cast"
    // before the first field was read.
    Map<Object?, Object?> engineShape() => {
          'gmsPresent': 'true',
          'gmsEnabled': 'true',
          'gmsVersionCode': '263234035',
          'gmsVersionName': '26.32.34',
          'vendingPresent': 'true',
          'gsfPresent': 'true',
          'customTabs': 'com.android.chrome',
          'hostGmsAvailable': 'true',
          'virtualGmsInstalled': 'false',
          'customTabsAvailable': 'true',
          'bridgesImplemented': false,
          'note': 'no flow is implemented',
        };

    test('parses the map the engine actually sends', () {
      final status = GoogleStatus.fromMap(engineShape());
      expect(status.gmsPresent, isTrue);
      expect(status.virtualGmsInstalled, isFalse);
      expect(status.bridgesImplemented, isFalse);
      expect(status.gmsVersionName, '26.32.34');
      expect(status.customTabsPackage, 'com.android.chrome');
      expect(status.presentButUnusable, isFalse);
    });

    test('parses real booleans too, so either shape is safe', () {
      final status = GoogleStatus.fromMap({
        ...engineShape(),
        'gmsPresent': true,
        'hostGmsAvailable': false,
      });
      expect(status.gmsPresent, isTrue);
      expect(status.hostGmsAvailable, isFalse);
      expect(status.presentButUnusable, isTrue);
    });

    test('a missing or unparseable flag is false, not an exception', () {
      final status = GoogleStatus.fromMap(const {'gmsPresent': 'yes'});
      expect(status.gmsPresent, isFalse);
      expect(status.gmsEnabled, isFalse);
      expect(status.gmsVersionName, '-');
    });
  });
}

void _localisationTests() {
  group('localisation', () {
    test('every string exists in both languages', () {
      // The whole reason the tables are plain maps rather than generated code. A key
      // added to one language and forgotten in the other shows an English sentence in a
      // Russian interface, which is the kind of thing nobody reports and everybody sees.
      expect(Strings.missingKeys(), isEmpty);
    });

    test('a Russian string is actually Russian', () {
      const en = Strings(Locale('en'));
      const ru = Strings(Locale('ru'));
      expect(en.t('settings.title'), 'Settings');
      expect(ru.t('settings.title'), isNot('Settings'));
      expect(ru.t('settings.title'), matches(RegExp(r'[А-Яа-я]')));
    });

    test('placeholders are filled, and an unknown key is returned as itself', () {
      const ru = Strings(Locale('ru'));
      expect(ru.t('add.added', {'app': 'Nagram'}), contains('Nagram'));
      expect(ru.t('no.such.key'), 'no.such.key');
    });

    test('a language names itself in its own language', () {
      // A picker that renames Русский to "Russian" is unreadable to the person who needs
      // it, so the names are deliberately not translated.
      expect(AppLanguage.russian.nativeName, 'Русский');
      expect(AppLanguage.english.nativeName, 'English');
      expect(AppLanguage.fromCode('ru'), AppLanguage.russian);
      expect(AppLanguage.fromCode(null), AppLanguage.system);
      expect(AppLanguage.system.locale, isNull);
    });
  });
}
