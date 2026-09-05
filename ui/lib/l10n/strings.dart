import 'package:flutter/material.dart';

/// Which language the interface is in.
///
/// `system` is the default and means "whatever the phone is set to", falling back to
/// English for a language UNIQUE has no strings for. The two explicit choices exist
/// because a phone's language and the language its owner wants to read a diagnostic in
/// are not always the same — someone testing a virtualization engine on a Russian device
/// may well want the English term for `SecurityException`, and someone else the opposite.
enum AppLanguage {
  system('system'),
  english('en'),
  russian('ru');

  const AppLanguage(this.code);

  final String code;

  static AppLanguage fromCode(String? code) =>
      AppLanguage.values.firstWhere((l) => l.code == code, orElse: () => AppLanguage.system);

  /// The locale to hand [MaterialApp], or null to follow the system.
  Locale? get locale => this == AppLanguage.system ? null : Locale(code);

  /// Always in its own language: a language picker that renames the languages into the
  /// current one is useless to the person who cannot read the current one.
  String get nativeName => switch (this) {
        AppLanguage.system => 'System',
        AppLanguage.english => 'English',
        AppLanguage.russian => 'Русский',
      };
}

/// The interface's own text, in each language it is written in.
///
/// Plain maps and a `t(key)` lookup rather than generated `AppLocalizations`. The gain
/// from codegen is compile-time checking of key names, and that is bought back here by
/// [missingKeys], which the widget tests assert is empty in both directions — so a key
/// added to one language and forgotten in the other fails the build rather than showing
/// an English sentence to a Russian reader.
///
/// A missing key falls back to English and then to the key itself, because a screen with
/// one untranslated line is usable and a screen that throws is not.
class Strings {
  const Strings(this.locale);

  final Locale locale;

  static const LocalizationsDelegate<Strings> delegate = _StringsDelegate();

  static const List<Locale> supportedLocales = [Locale('en'), Locale('ru')];

  static Strings of(BuildContext context) =>
      Localizations.of<Strings>(context, Strings) ?? const Strings(Locale('en'));

  bool get isRussian => locale.languageCode == 'ru';

  Map<String, String> get _table => isRussian ? _ru : _en;

  /// One string, with `{name}` placeholders filled from [args].
  String t(String key, [Map<String, Object?>? args]) {
    var value = _table[key] ?? _en[key] ?? key;
    if (args != null) {
      args.forEach((k, v) => value = value.replaceAll('{$k}', '${v ?? ''}'));
    }
    return value;
  }

  /// A string when there is one, and the engine's own text when there is not.
  ///
  /// The engine sends English labels for things it enumerates — permission groups above
  /// all — and translating them here keeps that vocabulary out of the Dart/Kotlin
  /// boundary. A group UNIQUE gains tomorrow shows its English label until this map
  /// catches up, which is the right failure: a name, not a key.
  String orElse(String key, String fallback) => _table[key] ?? _en[key] ?? fallback;

  /// Keys present in one language and missing from the other. Empty is the only
  /// acceptable answer; the widget tests hold it to that.
  static List<String> missingKeys() => [
        for (final k in _en.keys) if (!_ru.containsKey(k)) 'ru:$k',
        for (final k in _ru.keys) if (!_en.containsKey(k)) 'en:$k',
      ];

  // -------------------------------------------------------------------------------
  // English
  // -------------------------------------------------------------------------------
  static const Map<String, String> _en = {
    // Home
    'app.title': 'Unique',
    'home.search': 'Search',
    'home.searchClose': 'Close search',
    'home.searchHint': 'Search apps',
    'home.settings': 'Settings',
    'home.addApp': 'Add App',
    'home.empty.title': 'No apps yet',
    'home.empty.body': 'Add an installed app or an APK to give it its own space.',
    'home.menu.details': 'Details',
    'home.menu.clone': 'Clone',
    'home.menu.cloneBody': 'Create another independent instance',
    'home.menu.remove': 'Remove',
    'home.cloned': 'Instance created',
    'home.removed': 'Removed',
    'common.failed': 'That did not work.',
    'common.unknown': 'Unknown',
    'common.reading': 'Reading…',
    'common.none': '-',
    'common.tryAgain': 'Try again',
    'common.copy': 'Copy',

    // Startup
    'startup.failed.title': 'UNIQUE could not start',
    'startup.failed.body': 'The engine did not respond.',

    // Engine notices
    'engine.unsupported.title': 'Unsupported device',
    'engine.unsupported.body':
        'UNIQUE runs 64-bit ARM applications. This device does not report arm64-v8a support.',
    'engine.nativeFailed.title': 'Engine library did not load',
    'engine.nativeFailed.body': 'libunique_native could not be loaded.',
    'engine.restricted.title': 'Restricted platform access',
    'engine.restricted.body':
        'UNIQUE could not obtain the platform access it needs on this device, so virtual '
            'apps cannot be launched. Details are in Settings, Diagnostics.',
    'engine.degraded.title': 'The engine is not fully ready',
    'engine.degraded.body':
        'Something the engine needs is missing on this device. Settings, Diagnostics says '
            'which, and launching an app may fail until it is resolved.',

    // Add app
    'add.title': 'Add App',
    'add.tab.installed': 'Installed',
    'add.tab.apk': 'APK file',
    'add.searchHint': 'Search',
    'add.listFailed': 'Could not list applications',
    'add.systemApps': 'System apps',
    'add.importing': 'Importing…',
    'add.selectApk': 'Select APK',
    'add.chooseApk': 'Choose an APK from your files',
    'add.supportedTitle': 'Supported files',
    'add.supportedBody':
        'A single .apk, or a base APK together with its split APKs. UNIQUE keeps the '
            'arm64-v8a split and every feature split, and reports what it dropped.',
    'add.splitHint':
        'Select a base APK and its splits together — importing a base without its splits '
            'gives an app that starts and then cannot find its own code.',
    'add.failed': 'Could not add {app}.',
    'add.added': '{app} added',

    // App details
    'details.launch': 'Launch',
    'details.launching': 'Launching…',
    'details.stop': 'Stop',
    'details.launchUnavailable': 'Launch unavailable',
    'details.general': 'General',
    'details.package': 'Package',
    'details.versionCode': 'Version code',
    'details.instance': 'Instance',
    'details.permissions': 'Permissions',
    'details.noPermissions': 'None requested',
    'details.noPermissionsBody': 'This app asks for no runtime permissions',
    'details.hostMissing':
        'Not held by UNIQUE yet — turn this on and it will ask.',
    'details.allowed': 'Allowed',
    'details.notAllowed': 'Not allowed',
    'details.openSettings': 'Settings',
    'details.storage': 'Storage',
    'details.data': 'Data',
    'details.cache': 'Cache',
    'details.external': 'External',
    'details.clearCache': 'Clear cache',
    'details.cacheCleared': 'Cache cleared',
    'details.clearData': 'Clear data',
    'details.clearDataBody': 'Removes everything this instance stores',
    'details.deviceProfile': 'Device profile',
    'details.androidId': 'Android ID',
    'details.androidIdCopied': 'Android ID copied',
    'details.instanceId': 'Instance ID',
    'details.generation': 'Generation',
    'details.regenerate': 'Regenerate',
    'details.regenerateBody': 'Not available yet',
    'details.google': 'Google',
    'details.googlePresent': 'Play services on this device',
    'details.googleFlows':
        'Not implemented yet. What follows is how each flow would be routed if it were.',

    // Settings
    'settings.title': 'Settings',
    'settings.appearance': 'Appearance',
    'settings.language': 'Language',
    'settings.languageBody': 'Interface language',
    'settings.dynamicColor': 'Material You colours',
    'settings.dynamicColorBody': 'Follow the system wallpaper accent',
    'settings.reduceMotion': 'Reduce motion',
    'settings.reduceMotionBody': 'Shorter transitions',
    'settings.engine': 'Engine',
    'settings.platformAccess': 'Platform access',
    'settings.granted': 'Granted',
    'settings.denied': 'Denied — virtual apps cannot run',
    'settings.nativeLibrary': 'Native library',
    'settings.loaded': 'Loaded',
    'settings.notLoaded': 'Not loaded',
    'settings.pageSize': 'Memory page size',
    'settings.largePages': 'apps must ship 16 KB-aligned libraries',
    'settings.pathRedirection': 'Path redirection',
    'settings.active': 'Active',
    'settings.notActive': 'Not active in this build',
    'settings.google': 'Google',
    'settings.playServices': 'Play services',
    'settings.notInstalledHere': 'Not installed on this device',
    'settings.presentUnusable': 'Installed but not usable',
    'settings.disabledSuffix': 'disabled',
    'settings.available': 'Available',
    'settings.playStore': 'Play Store',
    'settings.installed': 'Installed',
    'settings.notInstalled': 'Not installed',
    'settings.oauthBrowser': 'Browser for OAuth',
    'settings.noBrowser': 'None — browser-based sign-in cannot run here',
    'settings.signInFlows': 'Sign-in flows',
    'settings.signInNotImplemented':
        'Not implemented yet — routing is decided and recorded, but no flow has an '
            'implementation',
    'settings.googleDiagnostics': 'Google diagnostics',
    'settings.advanced': 'Advanced',
    'settings.deviceTest': 'Device test',
    'settings.deviceTestBody':
        'What this phone is, and the physical-device sequence — run and recorded here, '
            'with no computer',
    'settings.diagnostics': 'Diagnostics',
    'settings.diagnosticsBody': '{count} events recorded',
    'settings.export': 'Export diagnostics',
    'settings.exporting': 'Collecting from every running app…',
    'settings.exportBody':
        'A zip with UNIQUE’s logs and the device, and nothing from inside a '
            'virtualized app',
    'settings.exportFailed': 'Export failed',
    'settings.savedTo': 'Saved to {path}',
    'settings.about': 'About',
    'settings.version': 'Version',
    'settings.android': 'Android',
    'settings.licences': 'Open-source licences',
    'settings.nothingRecorded': 'Nothing recorded yet',
    'settings.processes.one': 'process',
    'settings.processes.many': 'processes',
    'settings.exportSummary': '{name} — {size}, {lines} lines from {procs} {word}',

    // Device test
    'devtest.title': 'Device test',
    'devtest.reread': 'Re-read the device',
    'devtest.notice.title': 'Everything here happens on this phone',
    'devtest.notice.body':
        'No computer, no adb, no root. Work down the sequence in order — a failure early '
            'on explains the ones after it — then send the diagnostics package from the '
            'last step.',
    'devtest.sequence': 'Sequence  ·  {done} of {total} recorded',
    'devtest.send': 'Send the results',
    'devtest.share': 'Export and share diagnostics',
    'devtest.collecting': 'Collecting…',
    'devtest.shareBody':
        'The logs, this checklist and the device report. Nothing from inside any '
            'virtualized app.',
    'devtest.clear': 'Clear the sequence',
    'devtest.clearBody': 'Verdicts and notes only. Nothing else is touched.',
    'devtest.readingDevice': 'Reading the device…',
    'devtest.copied': '{key} copied',
    'devtest.pass': 'Pass',
    'devtest.fail': 'Fail',
    'devtest.blocked': 'Blocked',
    'devtest.skip': 'Skip',
    'devtest.note': 'What happened',
    'devtest.noteHint': 'Exact error text is worth more than a description of it',

    // Permission groups, keyed by the engine's own enum name so the two cannot drift.
    // The engine still sends an English label; it is the fallback, not the source.
    'perm.CAMERA': 'Camera',
    'perm.MICROPHONE': 'Microphone',
    'perm.LOCATION': 'Location',
    'perm.FILES': 'Files',
    'perm.NOTIFICATIONS': 'Notifications',
    'perm.CONTACTS': 'Contacts',
    'perm.CALENDAR': 'Calendar',
    'perm.NEARBY_DEVICES': 'Nearby devices',
    'perm.PHYSICAL_ACTIVITY': 'Physical activity',
    'perm.BODY_SENSORS': 'Body sensors',
    'perm.PHONE': 'Phone',
  };

  // -------------------------------------------------------------------------------
  // Русский
  // -------------------------------------------------------------------------------
  static const Map<String, String> _ru = {
    'app.title': 'Unique',
    'home.search': 'Поиск',
    'home.searchClose': 'Закрыть поиск',
    'home.searchHint': 'Поиск приложений',
    'home.settings': 'Настройки',
    'home.addApp': 'Добавить',
    'home.empty.title': 'Пока нет приложений',
    'home.empty.body':
        'Добавьте установленное приложение или APK, чтобы дать ему собственное пространство.',
    'home.menu.details': 'Подробности',
    'home.menu.clone': 'Клонировать',
    'home.menu.cloneBody': 'Создать ещё одну независимую копию',
    'home.menu.remove': 'Удалить',
    'home.cloned': 'Копия создана',
    'home.removed': 'Удалено',
    'common.failed': 'Не получилось.',
    'common.unknown': 'Неизвестно',
    'common.reading': 'Чтение…',
    'common.none': '—',
    'common.tryAgain': 'Повторить',
    'common.copy': 'Копировать',

    'startup.failed.title': 'UNIQUE не смог запуститься',
    'startup.failed.body': 'Движок не ответил.',

    'engine.unsupported.title': 'Устройство не поддерживается',
    'engine.unsupported.body':
        'UNIQUE запускает 64-битные ARM-приложения. Это устройство не сообщает о поддержке '
            'arm64-v8a.',
    'engine.nativeFailed.title': 'Библиотека движка не загрузилась',
    'engine.nativeFailed.body': 'Не удалось загрузить libunique_native.',
    'engine.restricted.title': 'Ограниченный доступ к платформе',
    'engine.restricted.body':
        'UNIQUE не смог получить нужный доступ к платформе на этом устройстве, поэтому '
            'виртуальные приложения не запускаются. Подробности — в «Настройки → '
            'Диагностика».',
    'engine.degraded.title': 'Движок готов не полностью',
    'engine.degraded.body':
        'На этом устройстве отсутствует что-то, что нужно движку. В «Настройки → '
            'Диагностика» указано что именно; до этого запуск приложения может не удаться.',

    'add.title': 'Добавить приложение',
    'add.tab.installed': 'Установленные',
    'add.tab.apk': 'Файл APK',
    'add.searchHint': 'Поиск',
    'add.listFailed': 'Не удалось получить список приложений',
    'add.systemApps': 'Системные приложения',
    'add.importing': 'Импорт…',
    'add.selectApk': 'Выбрать APK',
    'add.chooseApk': 'Выберите APK в файлах',
    'add.supportedTitle': 'Поддерживаемые файлы',
    'add.supportedBody':
        'Один .apk или базовый APK вместе со сплитами. UNIQUE оставляет сплит arm64-v8a и '
            'все функциональные сплиты и сообщает, что отброшено.',
    'add.splitHint':
        'Выбирайте базовый APK вместе со сплитами — импорт базового APK без сплитов даёт '
            'приложение, которое запускается и не находит собственный код.',
    'add.failed': 'Не удалось добавить {app}.',
    'add.added': '{app} добавлено',

    'details.launch': 'Запустить',
    'details.launching': 'Запуск…',
    'details.stop': 'Остановить',
    'details.launchUnavailable': 'Запуск недоступен',
    'details.general': 'Общее',
    'details.package': 'Пакет',
    'details.versionCode': 'Код версии',
    'details.instance': 'Копия',
    'details.permissions': 'Разрешения',
    'details.noPermissions': 'Не запрошены',
    'details.noPermissionsBody': 'Это приложение не запрашивает разрешений во время работы',
    'details.hostMissing':
        'У UNIQUE его пока нет — включите, и он его запросит.',
    'details.allowed': 'Разрешено',
    'details.notAllowed': 'Запрещено',
    'details.openSettings': 'Настройки',
    'details.storage': 'Хранилище',
    'details.data': 'Данные',
    'details.cache': 'Кэш',
    'details.external': 'Внешнее',
    'details.clearCache': 'Очистить кэш',
    'details.cacheCleared': 'Кэш очищен',
    'details.clearData': 'Стереть данные',
    'details.clearDataBody': 'Удаляет всё, что хранит эта копия',
    'details.deviceProfile': 'Профиль устройства',
    'details.androidId': 'Android ID',
    'details.androidIdCopied': 'Android ID скопирован',
    'details.instanceId': 'ID копии',
    'details.generation': 'Поколение',
    'details.regenerate': 'Сгенерировать заново',
    'details.regenerateBody': 'Пока недоступно',
    'details.google': 'Google',
    'details.googlePresent': 'Play-сервисы на этом устройстве',
    'details.googleFlows':
        'Пока не реализовано. Ниже — как каждый сценарий был бы направлен, если бы был.',

    'settings.title': 'Настройки',
    'settings.appearance': 'Оформление',
    'settings.language': 'Язык',
    'settings.languageBody': 'Язык интерфейса',
    'settings.dynamicColor': 'Цвета Material You',
    'settings.dynamicColorBody': 'Следовать акценту системных обоев',
    'settings.reduceMotion': 'Меньше анимации',
    'settings.reduceMotionBody': 'Более короткие переходы',
    'settings.engine': 'Движок',
    'settings.platformAccess': 'Доступ к платформе',
    'settings.granted': 'Предоставлен',
    'settings.denied': 'Отказано — виртуальные приложения не запустятся',
    'settings.nativeLibrary': 'Нативная библиотека',
    'settings.loaded': 'Загружена',
    'settings.notLoaded': 'Не загружена',
    'settings.pageSize': 'Размер страницы памяти',
    'settings.largePages': 'библиотеки приложений должны быть выровнены на 16 КБ',
    'settings.pathRedirection': 'Перенаправление путей',
    'settings.active': 'Активно',
    'settings.notActive': 'В этой сборке не активно',
    'settings.google': 'Google',
    'settings.playServices': 'Play-сервисы',
    'settings.notInstalledHere': 'Не установлены на этом устройстве',
    'settings.presentUnusable': 'Установлены, но непригодны',
    'settings.disabledSuffix': 'отключены',
    'settings.available': 'Доступны',
    'settings.playStore': 'Play Маркет',
    'settings.installed': 'Установлен',
    'settings.notInstalled': 'Не установлен',
    'settings.oauthBrowser': 'Браузер для OAuth',
    'settings.noBrowser': 'Нет — вход через браузер здесь невозможен',
    'settings.signInFlows': 'Сценарии входа',
    'settings.signInNotImplemented':
        'Пока не реализованы — маршрут выбирается и записывается, но ни один сценарий не '
            'имеет реализации',
    'settings.googleDiagnostics': 'Диагностика Google',
    'settings.advanced': 'Дополнительно',
    'settings.deviceTest': 'Тест устройства',
    'settings.deviceTestBody':
        'Что это за телефон и последовательность проверок на устройстве — выполняется и '
            'записывается здесь, без компьютера',
    'settings.diagnostics': 'Диагностика',
    'settings.diagnosticsBody': 'Записано событий: {count}',
    'settings.export': 'Экспорт диагностики',
    'settings.exporting': 'Сбор со всех запущенных приложений…',
    'settings.exportBody':
        'Архив с журналами UNIQUE и описанием устройства — и ничего из виртуализированного '
            'приложения',
    'settings.exportFailed': 'Экспорт не удался',
    'settings.savedTo': 'Сохранено в {path}',
    'settings.about': 'О программе',
    'settings.version': 'Версия',
    'settings.android': 'Android',
    'settings.licences': 'Лицензии открытого кода',
    'settings.nothingRecorded': 'Пока ничего не записано',
    'settings.processes.one': 'процесса',
    'settings.processes.many': 'процессов',
    'settings.exportSummary': '{name} — {size}, строк: {lines}, из {procs} {word}',

    'devtest.title': 'Тест устройства',
    'devtest.reread': 'Перечитать устройство',
    'devtest.notice.title': 'Всё это происходит на самом телефоне',
    'devtest.notice.body':
        'Без компьютера, без adb, без root. Идите по шагам по порядку — сбой в начале '
            'объясняет всё, что после него, — затем отправьте пакет диагностики с '
            'последнего шага.',
    'devtest.sequence': 'Последовательность  ·  записано {done} из {total}',
    'devtest.send': 'Отправить результаты',
    'devtest.share': 'Экспортировать и отправить диагностику',
    'devtest.collecting': 'Сбор…',
    'devtest.shareBody':
        'Журналы, этот список и отчёт об устройстве. Ничего из виртуализированных '
            'приложений.',
    'devtest.clear': 'Очистить список',
    'devtest.clearBody': 'Только вердикты и заметки. Больше ничего не затрагивается.',
    'devtest.readingDevice': 'Чтение устройства…',
    'devtest.copied': '{key} скопировано',
    'devtest.pass': 'Прошло',
    'devtest.fail': 'Сбой',
    'devtest.blocked': 'Блокировано',
    'devtest.skip': 'Пропустить',
    'devtest.note': 'Что произошло',
    'devtest.noteHint': 'Точный текст ошибки ценнее, чем её описание',

    'perm.CAMERA': 'Камера',
    'perm.MICROPHONE': 'Микрофон',
    'perm.LOCATION': 'Местоположение',
    'perm.FILES': 'Файлы',
    'perm.NOTIFICATIONS': 'Уведомления',
    'perm.CONTACTS': 'Контакты',
    'perm.CALENDAR': 'Календарь',
    'perm.NEARBY_DEVICES': 'Устройства поблизости',
    'perm.PHYSICAL_ACTIVITY': 'Физическая активность',
    'perm.BODY_SENSORS': 'Датчики тела',
    'perm.PHONE': 'Телефон',
  };
}

class _StringsDelegate extends LocalizationsDelegate<Strings> {
  const _StringsDelegate();

  @override
  bool isSupported(Locale locale) =>
      Strings.supportedLocales.any((l) => l.languageCode == locale.languageCode);

  @override
  Future<Strings> load(Locale locale) async => Strings(locale);

  @override
  bool shouldReload(_StringsDelegate old) => false;
}
