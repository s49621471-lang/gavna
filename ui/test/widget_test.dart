import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:unique_ui/models/models.dart';
import 'package:unique_ui/theme/unique_theme.dart';
import 'package:unique_ui/widgets/common.dart';

void main() {
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
      expect(blocked.blockedReason, contains('arm64-v8a'));

      final ok = InstalledApp.fromMap({
        'package': 'com.example.app',
        'label': 'Example',
        'hasArm64': true,
      });
      expect(ok.blockedReason, isNull);
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
}
