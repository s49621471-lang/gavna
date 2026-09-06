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
  String orElse(String key, String fallback, [Map<String, Object?>? args]) {
    var value = _table[key] ?? _en[key];
    if (value == null) return fallback;
    if (args != null) {
      args.forEach((k, v) => value = value!.replaceAll('{$k}', '${v ?? ''}'));
    }
    return value!;
  }

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
    'common.cancel': 'Cancel',

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
            'apps cannot be launched. Settings says what is missing.',
    'engine.degraded.title': 'The engine is not fully ready',
    'engine.degraded.body':
        'Something the engine needs is missing on this device. Settings says which, and '
            'launching an app may fail until it is resolved.',

    // Why an engine action failed, keyed by the code the engine sends.
    //
    // A code with no entry here falls back to the engine's own English sentence, which is
    // the right failure: specific text in one language beats an identifier in none. The
    // sentences are deliberately not translations of the engine's — they say the same
    // thing to a person rather than to a developer.
    'engine.NO_ARM64': 'This app has no 64-bit ARM code. UNIQUE runs arm64-v8a only.',
    'engine.NOT_ALIGNED_16K':
        "This app's native libraries do not fit this device's memory pages. Only its "
            'developer can fix that.',
    'engine.APK_UNREADABLE': "Could not read the app's APK files.",
    'engine.IMPORT_UNSUPPORTED': 'The app could not be installed into UNIQUE.',
    'engine.NOT_INSTALLED': 'That app is no longer installed on this device.',
    'engine.NOT_IMPORTED': 'That app has not been added to UNIQUE.',
    'engine.MANIFEST_UNREADABLE': "Could not read the app's manifest.",
    'engine.RECORD_FAILED': 'Could not save the new copy.',
    'engine.DOWNGRADE':
        'That file is older than the version already added. An older build must not read '
            "a newer build's data.",
    'engine.SIGNER_MISMATCH':
        'That update is signed by a different developer, so it cannot replace this app.',
    'engine.SIGNER_UNKNOWN':
        'Could not verify that the update is signed by the same developer.',
    'engine.NO_SUCH_INSTANCE': 'That copy no longer exists.',
    'engine.NO_LAUNCHABLE_ACTIVITY': 'This app has no screen UNIQUE can open.',
    'engine.PROCESS_POOL_EXHAUSTED':
        'Every virtual process is in use. Stop an app and try again.',
    'engine.START_ACTIVITY_FAILED': 'Android refused to open the app.',
    'engine.NO_PICKER': "Open UNIQUE's main screen and try again.",
    'engine.NO_FILE_READ': 'None of the selected files could be read.',
    'engine.BAD_SETTING_VALUE': 'That setting could not be saved.',
    'engine.UNKNOWN_PERMISSION_GROUP': 'Unknown permission.',
    'engine.NEEDS_MAIN_SCREEN': "Open UNIQUE's main screen to grant {group}.",
    'engine.HOST_PERMISSION_REFUSED':
        '{group} was not granted to UNIQUE, so it cannot be granted to this app.',
    'engine.HOST_PERMISSION_DENIED_FOREVER':
        'Android will not ask again for {group}. Grant it to UNIQUE in system settings, '
            'then try again.',
    'engine.PERMISSION_NOT_REQUESTED': 'This app does not ask for {group}.',
    'engine.NO_SUCH_ACCESS': 'There is no such access.',
    'engine.NO_SETTINGS_SCREEN': 'This device has no settings screen for that.',

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
    'add.blocked.noArm64': 'No 64-bit ARM code. UNIQUE runs arm64-v8a only.',
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
    // "Profile 2" as the engine stored it, said in the reader's own language.
    'profile.name': 'Profile {n}',
    'details.instance': 'Instance',
    'details.permissions': 'Permissions',
    'details.noPermissions': 'None requested',
    'details.noPermissionsBody': 'This app asks for no runtime permissions',
    'details.hostMissing':
        'Not held by UNIQUE yet — turn this on and it will ask.',
    'details.allowed': 'Allowed',
    'details.notAllowed': 'Not allowed',
    'details.openSettings': 'Settings',
    'details.specialAccess': 'Special access',
    'details.specialAccessBody':
        'Granted to UNIQUE, so it applies to every app inside it. Android has no dialog '
        'for these — each one opens its own Settings screen.',
    'access.notGranted': 'Not granted — tap to open',
    'access.overlay': 'Draw over other apps',
    'access.exactAlarm': 'Alarms and reminders',
    'access.battery': 'Run in the background unrestricted',
    'access.allFiles': 'Access to all files',
    'launch.assetsUnreadable':
        'Started, but this app\u2019s downloaded game files could not be copied in \u2014 '
        'Android keeps Android/obb closed unless UNIQUE has access to all files.',
    'launch.grantAllFiles': 'Allow',
    'details.storage': 'Storage',
    'details.data': 'Data',
    'details.cache': 'Cache',
    'details.external': 'External',
    'details.clearCache': 'Clear cache',
    'details.cacheCleared': 'Cache cleared',
    'details.clearData': 'Clear data',
    'details.clearDataBody': 'Removes everything this instance stores',
    'details.clearDataTitle': 'Erase {app} data?',
    'details.clearDataConfirm':
        'Everything {profile} has stored is deleted: files, databases and settings. '
        'Other copies of this app are not touched.',
    'details.clearDataAction': 'Erase',
    'details.dataCleared': 'Data erased',
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
    'settings.about': 'About',
    'settings.version': 'Version',
    'settings.android': 'Android',
    'settings.licences': 'Open-source licences',

    // Device test

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
    'common.cancel': 'Отмена',

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
            'виртуальные приложения не запускаются. В настройках указано, чего не хватает.',
    'engine.degraded.title': 'Движок готов не полностью',
    'engine.degraded.body':
        'На этом устройстве отсутствует что-то, что нужно движку. В настройках указано '
            'что именно; до этого запуск приложения может не удаться.',

    // Почему действие движка не удалось — по коду, который присылает движок.
    'engine.NO_ARM64':
        'В приложении нет 64-битного кода ARM. UNIQUE работает только с arm64-v8a.',
    'engine.NOT_ALIGNED_16K':
        'Библиотеки приложения не подходят под размер страниц памяти этого устройства. '
            'Исправить это может только его разработчик.',
    'engine.APK_UNREADABLE': 'Не удалось прочитать файлы APK приложения.',
    'engine.IMPORT_UNSUPPORTED': 'Не удалось установить приложение в UNIQUE.',
    'engine.NOT_INSTALLED': 'Это приложение больше не установлено на устройстве.',
    'engine.NOT_IMPORTED': 'Это приложение не добавлено в UNIQUE.',
    'engine.MANIFEST_UNREADABLE': 'Не удалось прочитать манифест приложения.',
    'engine.RECORD_FAILED': 'Не удалось сохранить новую копию.',
    'engine.DOWNGRADE':
        'Этот файл старее уже добавленной версии. Старая сборка не должна читать данные '
            'новой.',
    'engine.SIGNER_MISMATCH':
        'Обновление подписано другим разработчиком, поэтому оно не может заменить это '
            'приложение.',
    'engine.SIGNER_UNKNOWN':
        'Не удалось проверить, что обновление подписано тем же разработчиком.',
    'engine.NO_SUCH_INSTANCE': 'Этой копии больше нет.',
    'engine.NO_LAUNCHABLE_ACTIVITY': 'У приложения нет экрана, который UNIQUE может открыть.',
    'engine.PROCESS_POOL_EXHAUSTED':
        'Все виртуальные процессы заняты. Остановите какое-нибудь приложение и повторите.',
    'engine.START_ACTIVITY_FAILED': 'Android отказался открыть приложение.',
    'engine.NO_PICKER': 'Откройте главный экран UNIQUE и повторите.',
    'engine.NO_FILE_READ': 'Ни один из выбранных файлов не удалось прочитать.',
    'engine.BAD_SETTING_VALUE': 'Не удалось сохранить эту настройку.',
    'engine.UNKNOWN_PERMISSION_GROUP': 'Неизвестное разрешение.',
    'engine.NEEDS_MAIN_SCREEN':
        'Откройте главный экран UNIQUE, чтобы выдать разрешение «{group}».',
    'engine.HOST_PERMISSION_REFUSED':
        'Разрешение «{group}» не выдано самому UNIQUE, поэтому его нельзя выдать этому '
            'приложению.',
    'engine.HOST_PERMISSION_DENIED_FOREVER':
        'Android больше не спросит про «{group}». Выдайте это разрешение UNIQUE в '
            'настройках системы и повторите.',
    'engine.PERMISSION_NOT_REQUESTED': 'Это приложение не запрашивает «{group}».',
    'engine.NO_SUCH_ACCESS': 'Такого доступа нет.',
    'engine.NO_SETTINGS_SCREEN': 'На этом устройстве нет экрана настроек для этого.',

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
    'add.blocked.noArm64': 'Нет 64-битного кода ARM. UNIQUE работает только с arm64-v8a.',
    'add.failed': 'Не удалось добавить {app}.',
    'add.added': '{app} добавлено',

    'details.launch': 'Запустить',
    'details.launching': 'Запуск…',
    'details.stop': 'Остановить',
    'details.launchUnavailable': 'Запуск недоступен',
    'details.general': 'Общее',
    'details.package': 'Пакет',
    'details.versionCode': 'Код версии',
    'profile.name': 'Профиль {n}',
    'details.instance': 'Копия',
    'details.permissions': 'Разрешения',
    'details.noPermissions': 'Не запрошены',
    'details.noPermissionsBody': 'Это приложение не запрашивает разрешений во время работы',
    'details.hostMissing':
        'У UNIQUE его пока нет — включите, и он его запросит.',
    'details.allowed': 'Разрешено',
    'details.notAllowed': 'Запрещено',
    'details.openSettings': 'Настройки',
    'details.specialAccess': 'Особый доступ',
    'details.specialAccessBody':
        'Выдаётся самому UNIQUE, поэтому действует сразу для всех приложений внутри. '
        'Диалога для них в Android нет — каждый открывает свой экран настроек.',
    'access.notGranted': 'Не выдан — нажмите, чтобы открыть',
    'access.overlay': 'Поверх других приложений',
    'access.exactAlarm': 'Будильники и напоминания',
    'access.battery': 'Работа в фоне без ограничений',
    'access.allFiles': 'Доступ ко всем файлам',
    'launch.assetsUnreadable':
        'Запущено, но скачанные файлы игры скопировать не удалось \u2014 Android '
        'закрывает Android/obb, пока у UNIQUE нет доступа ко всем файлам.',
    'launch.grantAllFiles': 'Разрешить',
    'details.storage': 'Хранилище',
    'details.data': 'Данные',
    'details.cache': 'Кэш',
    'details.external': 'Внешнее',
    'details.clearCache': 'Очистить кэш',
    'details.cacheCleared': 'Кэш очищен',
    'details.clearData': 'Стереть данные',
    'details.clearDataBody': 'Удаляет всё, что хранит эта копия',
    'details.clearDataTitle': 'Стереть данные {app}?',
    'details.clearDataConfirm':
        'Будет удалено всё, что хранит копия «{profile}»: файлы, базы данных и '
        'настройки. Другие копии этого приложения не тронуты.',
    'details.clearDataAction': 'Стереть',
    'details.dataCleared': 'Данные стёрты',
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
    'settings.about': 'О программе',
    'settings.version': 'Версия',
    'settings.android': 'Android',
    'settings.licences': 'Лицензии открытого кода',


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
