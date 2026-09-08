# Changelog

Все заметные изменения проекта документируются в этом файле.
Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/),
версионирование — [SemVer](https://semver.org/lang/ru/).

## [Unreleased]

## [0.4.0] - 2026-09-08

### Добавлено
- **SIM-информация** в пересылаемых событиях: какой слот и оператор
  («Sim1 beeline») — для SMS (реальный subscriptionId) и пропущенных вызовов
  (первая активная SIM)

## [0.3.0] - 2026-09-08

### Изменено
- **Убрана поддержка MTProto/TDLib** (нативная библиотека не грузилась на
  устройствах → краши): удалён `TdClient`, зависимость `lib-tdlib-android`,
  `Prefs.useMtproto`. Остался только Bot API (HTTPS)
- **Прокси только HTTP/SOCKS5**: убраны «Без прокси» и «MTProto-proxy» из
  выбора; переключатель «Использовать прокси» включает/выключает прокси
- **minSdk 29** — поддержка Android 10+

### Добавлено
- **Автообновление через GitHub Releases**: при запуске проверяется последний
  релиз, при наличии новой версии — диалог со скачиванием APK и установкой
  (`UpdateChecker`, `DownloadReceiver`, FileProvider)
- **Английская локаль** (values-en), `resConfigs("ru", "en")`
- **Тесты**: whitelist-логика SmsFilter, compareVersions; тесты в release-джобе CI
- **Облегчение дистрибутива**: ABI splits (arm64-v8a, armeabi-v7a) — два
  отдельных APK вместо одного большого

## [0.2.0] - 2026-09-08

### Добавлено
- **TDLib (MTProto) как основной канал отправки** (`TdClient`):
  - Авторизация бота через `CheckAuthenticationBotToken` (без телефона)
  - Прокси HTTP / SOCKS5 / **MTProto-proxy** (`AddProxy` + `EnableProxy`)
  - Сессия в `filesDir/tdlib` (пере-авторизация не нужна)
  - Fallback на Bot API при ошибке TDLib (`sendEither`)
- Переключатель режима `useMtproto` в настройках
- JitPack-зависимость `com.github.capullo-tech:lib-tdlib-android` (prebuilt AAR,
  нативные .so в jniLibs) — фиксированный коммит `11850efeb5`
- **Онбординг** (`OnboardingActivity`, ViewPager2): приветствие → токен → chat ID → готово
  - Автоопределение chat ID через `resolveChatId`
  - Повторные запуски сразу в `MainActivity` (`Prefs.onboardingComplete`)
- **Детальные фильтры SMS** (`SmsFilter`): режимы all / contacts / whitelist, block-regex
  - UI-секция «Фильтры SMS» (режим, белый список, regex)
  - `SmsReceiver` переведён на `SmsFilter.shouldForward`
- **Релизная сборка APK**: подписанный release-APK в CI, GitHub Release по тегам v*.
  Keystore передаётся через секреты (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`)

## [0.1.0] - 2026-09-07

### Добавлено
- Скелет Android-приложения (Kotlin, Gradle 8.7, AGP 8.5.2, minSdk 26 / target 34)
- Пересылка входящих SMS (BroadcastReceiver, формат с именем из контактов)
- Пересылка пропущенных вызовов (PHONE_STATE + CallLog, только реально пропущенные)
- Foreground Service (START_STICKY, автостарт после перезагрузки, невидимое уведомление)
- Очередь событий с ретраями (экспоненциальный backoff, кап 5 мин)
- Настройки: токен бота + chat ID в UI, шифрованное хранилище (EncryptedSharedPreferences)
- Проверка подключения (тестовое сообщение)
- Фильтр коротких номеров (банки/реклама)
- Поддержка прокси HTTP/SOCKS5 (Bot API путь); MTProto-proxy — реализован на этапе TDLib
- TDLib (MTProto) — см. [Unreleased]
- GitLab CI/CD: build + test (все ветки), release + publish (теги vX.Y.Z), SemVer
- ТЗ: docs/TECH_TASK.md

### Планируется
- Onboarding-экраны
- Детальные фильтры SMS (regex, по номерам)
- Статус-экран со счётчиками и логом

[0.1.0]: https://gitlab.com/ozyab09/sms-forwarder/-/tags/v0.1.0