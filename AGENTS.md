# AGENTS.md — Technical Guide for SMS Forwarder

> Этот файл — полная техническая документация для разработчиков и ИИ-агентов.
> README.md — для пользователей (дружелюбный, скриншоты, минимум технических деталей).
> Актуальный план работ — ROADMAP.md; история изменений — CHANGELOG.md.

---

## 🎯 Project Overview

**SMS Forwarder** — Android app forwarding incoming/outgoing SMS, incoming/outgoing/missed calls, and app notifications to Telegram via Bot API.
- Min SDK: 29 (Android 10), Target: 34 (Android 14)
- Language: Kotlin 2.2, Gradle Kotlin DSL (Gradle 9.3, AGP 9.1)
- Architecture: Clean separation — UI (MVVM), Receivers, Foreground Service, Telegram channels
- Privacy-first: encrypted token storage, no logs leave device, no analytics

---

## 🏗️ Architecture Details

```
┌─────────────────────────────────────────────────────────────────────┐
│  UI Layer (MainActivity — тонкий View)                              │
│  └─ MainViewModel (StateFlow<SettingsUiState> + SharedFlow<UiEvent>)│
│  - Вкладки: Настройки / История / Логи / О приложении               │
│  - Storage: DataStore (plain) + EncryptedSharedPreferences (секреты)│
└────────────────────────────┬────────────────────────────────────────┘
                             │ Prefs (cache) / EventHistory (Room)
┌────────────────────────────▼────────────────────────────────────────┐
│  Broadcast Receivers & Listeners (system-triggered)                 │
│  ├─ SmsReceiver:        SMS_RECEIVED → формат по шаблону            │
│  ├─ CallReceiver:       PHONE_STATE + CallLog → missed/incoming/outgoing │
│  ├─ OutgoingSmsObserver: ContentObserver на content://sms/sent      │
│  └─ BootReceiver:       BOOT_COMPLETED → start ForwardService       │
└────────────────────────────┬────────────────────────────────────────┘
                             │ Intent extras (QueuedEvent)
┌────────────────────────────▼────────────────────────────────────────┐
│  ForwardService (Foreground, START_STICKY, type dataSync)           │
│  ├─ SendQueue: FIFO + min-heap ретраев (per-event backoff)          │
│  ├─ EventQueueStore: атомарная персистентность очереди на диск      │
│  ├─ EventHistory: Room (статус, канал, chatId, бот, текст)          │
│  └─ ChannelSender → ChannelClientFactory (кэш OkHttp) → Bot API     │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Classes

| Package | Class | Responsibility |
|---------|-------|----------------|
| `ui` | `MainActivity` | Тонкая склейка панелей: вкладки, SAF, разрешения, рендер состояния |
| `ui` | `ChannelsPanel` | Панель «Каналы»: список, порядок, диалоги прокси (выделена из MainActivity) |
| `ui` | `HistoryPanel` | Панель «История»: фильтры, список, детали события (выделена из MainActivity) |
| `ui` | `MainViewModel` | Состояние экрана + операции (тест каналов, getMyId), MVVM |
| `ui` | `OnboardingActivity` | Первый запуск: приветствие → токен → Chat ID → готово |
| `receiver` | `SmsReceiver` | Входящие SMS, формат по шаблону |
| `receiver` | `CallReceiver` | PHONE_STATE: missed/incoming/outgoing, подтверждение через CallLog |
| `receiver` | `BootReceiver` | Автозапуск сервиса после перезагрузки |
| `service` | `ForwardService` | Foreground-сервис: очередь, per-event ретраи, история |
| `service` | `SendQueue` | FIFO новых событий + min-heap ретраев, MAX_ATTEMPTS |
| `service` | `EventQueueStore` | Персистентность очереди (tmp + rename) — события не теряются |
| `service` | `OutgoingSmsObserver` | ContentObserver на content://sms/sent — исходящие SMS |
| `telegram` | `Channel` | Канал отправки: direct / http / socks5 (+ `ChannelStore`) |
| `telegram` | `ChannelSender` | Каскадная отправка + `testAll` (getMe, лимит 4) |
| `telegram` | `ChannelClientFactory` | Кэш OkHttp-клиентов по конфигурации канала |
| `telegram` | `TelegramClient` | Bot API: sendMessage / getUpdates / getMe |
| `history` | `EventHistory` | Запись/чтение истории (Room) — вкладка «История» |
| `update` | `UpdateChecker` / `UpdateManager` | Проверка GitHub Releases, диалог, загрузка APK |
| `util` | `Prefs` | DataStore (plain) + EncryptedSharedPreferences; **async init** |
| `util` | `LogStore` | Кольцевой буфер логов (последние 200 записей, in-memory) |
| `util` | `TemplateFormatter` | Стандартный формат пересылаемых сообщений (шаблоны удалены) |
| `util` | `QuietHours` | Тихие часы (интервалы, в т.ч. через полночь) |
| `util` | `SettingsBackup` | Экспорт/импорт настроек (JSON, без секретов) |
| `util` | `ThemeManager` | Светлая/тёмная/системная тема |
| `util` | `ContactNames` / `SimInfo` / `ReceiverExecutor` | Имя контакта, SIM, фоновая работа ресиверов (`goAsync`) |

---

## 🔐 Security Model

| Data | Storage | Encryption |
|------|---------|------------|
| Bot token | `EncryptedSharedPreferences` | AES256-GCM (MasterKey) |
| Proxy password, channels JSON | `EncryptedSharedPreferences` | AES256-GCM |
| Chat ID, proxy host/port, toggles | DataStore (plain) | None (not secret) |
| Bot username (кэш для истории) | DataStore (plain) | None (не секрет) |
| Event log | In-memory ring buffer (200) | Never persisted |
| История событий | Room (`event_history.db`, локально) | SQLite, не шифруется — секретов нет |

**No** data leaves device except via user's Telegram bot over HTTPS.
**No** crash reporting, analytics, or network calls except Bot API + GitHub Releases (проверка обновлений).
При экспорте настроек секреты (токен, пароли прокси) в файл **не** попадают.

---

## 🌐 Channels & Proxy

- Каналы отправки — список `Channel` (прямое соединение + HTTP/SOCKS5 прокси),
  хранится как JSON в secure prefs (`Prefs.channelsJson`), прямой канал всегда
  первый и неотключаемый.
- Типы прокси: `http`, `socks5`. Basic auth работает для HTTP; SOCKS5 с логином/
  паролем отклоняется с явной ошибкой (OkHttp не умеет авторизацию SOCKS5).
- `ChannelSender.testAll` проверяет все каналы параллельно через `getMe`
  (лимит 4, сообщения не отправляются; результат — username бота по каждому
  каналу), каскадная отправка с общим таймаутом (callTimeout 30с, каскад ≤ 120с).
- Порядок каналов полностью динамический (issue #123): promote-on-success —
  успешный канал поднимается наверх (`ChannelStore.promote`), demote-on-failure —
  неудачный уходит в конец (`ChannelStore.demote`). Работает и для direct:
  его позиция НЕ закреплена (fix в `setAll`/`cachedOrLoad`). Переключатель
  enabled при отправке не меняется никогда — только пользователь.
  «Без прокси» нельзя удалить (`remove(direct)` — no-op) и выключить.

---

## 📱 Foreground Service Details

| Aspect | Implementation |
|--------|----------------|
| Type | `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (Android 14+) |
| Lifecycle | `START_STICKY` — system restarts if killed |
| Notification | Channel `IMPORTANCE_MIN` (invisible on lock screen) |
| Battery | Requests `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Vendor quirks | Onboarding shows vendor-specific autostart instructions |
| Queue | `SendQueue`: FIFO для новых событий + min-heap ретраев; персистентность в `filesDir/event_queue.json` (события не теряются при смерти процесса) |
| Retry | per-event: 15с → 30с → … кап 10 мин; после 8 попыток событие отбрасывается с записью в лог и историю (`dropped`) |
| Blocking | Новые события обрабатываются немедленно — бэк-офф упавших не блокирует очередь (нет head-of-line blocking) |
| Stop | «Стоп» сервиса отбрасывает очередь (память + файл) — остановка означает остановку пересылки |

---

## 🧪 Testing

```bash
# Unit tests (JVM, no device):
./gradlew testDebugUnitTest

# Lint (catches common Android issues):
./gradlew lintDebug

# Connected tests (requires device/emulator):
./gradlew connectedDebugAndroidTest
```

> Для локального прогона нужен Android SDK (`ANDROID_HOME` или `local.properties`
> с `sdk.dir`). Без SDK проверка — через CI (PR: build + test + lint).

### Test Coverage Areas (~182 теста; 0 пропускаются — последний @Ignore расконсервирован в #139)
- `MainViewModel` — load/save, testConnection (успех/ошибки/нет токена), resolveChatId
- `SendQueue` — порядок FIFO/ретраев, per-event backoff, отброс после попыток
- `ChannelStore` / `ChannelSender` / `ChannelClientFactory` — приоритет, promote, testAll, прокси-конфиг
- `TemplateFormatter` — плейсхолдеры, дефолты, предпросмотр
- `QuietHours` — интервалы, в т.ч. через полночь
- `EventQueueStore` — персистентность очереди (атомарная запись, битый файл, лимит)
- `LogStore`, `SettingsBackup` — кольцевой буфер, privacy-first экспорт/импорт (включая настройки звонков)
- `UtilTest` — сравнение версий, ссылки обновлений
- `MainActivityLaunchTest` — регрессии главного экрана (холодный старт, поворот, вкладки)
- `ForwardServiceNotificationTest` — FGS-канал IMPORTANCE_MIN, без heads-up
- `ReceiverTest` — state machine вызовов (missed/incoming/outgoing) + guards

Не покрыто: интеграционные сценарии на реальном устройстве (FGS-старт из фона, OEM-поведение PHONE_STATE) — только ручная проверка.

---

## 🔄 CI/CD Pipeline (`.github/workflows/build.yml`)

### Triggers
| Event | Jobs Run |
|-------|----------|
| `pull_request` | `build` (debug APK + unit-тесты + lintDebug) |
| `push` `main` | `release-tag` (создаёт тег из версии в `libs.versions.toml`) |
| tag `v*` / `workflow_dispatch` на теге | `release` (unit-тесты + signed APK + GitHub Release) |

### Jobs
```yaml
build:
  if: pull_request
  steps: checkout → setup-java (temurin 17) → setup-gradle@v4 →
         assembleDebug + testDebugUnitTest + lintDebug (--parallel --build-cache) →
         upload-artifact (debug APK)

release-tag:
  if: push to main
  steps: checkout → вычисляет следующий semver из последнего тега
         (patch+1; feat: → minor+1; BREAKING CHANGE → major+1, #128) →
         создаёт тег v{major}.{minor}.{patch} через API (push не нужен) →
         workflow_dispatch на теге
  # Бота-пуша в main больше нет: branch protection требует PR для всех
  # изменений. Номер версии вычисляется CI из последнего тега (#128).

release:
  if: startsWith(github.ref, 'refs/tags/v')
  steps: checkout → setup-java → setup-gradle@v4 →
         testDebugUnitTest (релиз не собирается из непротестированного кода) →
         assembleRelease (KEYSTORE_* secrets) →
         upload-artifact → extract-changelog (awk из CHANGELOG.md) →
         softprops/action-gh-release@v2 (body_path)
```

### Key CI Details
- `gradle/actions/setup-gradle@v4` — Gradle кэширование
- `--parallel --build-cache` — ускорение сборки
- **Branch protection на main**: все изменения через PR с зелёным CI (build + test + lint). Прямые push отклоняются (GH013) — в т.ч. для бота, поэтому auto-bump коммитов нет (#95)
- Release notes: извлекаются из `CHANGELOG.md` через awk (секция `## [X.Y.Z]`); fallback — git log между тегами
- Node.js 20 deprecation warning от upload-artifact@v4 / setup-gradle@v4 / action-gh-release@v2 — известная косметика, actions работают на Node 24 принудительно; обновление — отдельным PR

### Versioning (SemVer)
- Tags: `v<major>.<minor>.<patch>` (e.g. `v1.2.0`) — создаются **автоматически при мерже в main** из версии в `libs.versions.toml` (только тег, без bump-коммита)
- `versionName` = tag without `v`
- `versionCode` = `major*10000 + minor*100 + patch`
- Source: git-теги (последний тег + bump по conventional commits, #128);
  в libs.versions.toml версии приложения НЕТ — только зависимости.
  Локальные сборки: последний тег + patch+1 (см. app/build.gradle.kts)
- Версия вычисляется автоматически из последнего тега (#128): patch+1 по умолчанию,
  `feat:` в коммитах с прошлого тега → minor+1, `BREAKING CHANGE`/`!:` → major+1.
  Используй conventional commit-префиксы (`feat:`, `fix:`) в squash-заголовке PR

### Required Secrets (GitHub → Settings → Secrets → Actions)
| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded JKS/PKCS12 keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password (same as keystore for PKCS12) |

---

## 📦 Release Process

```bash
# 1. В ветке с фичей добавить описание в CHANGELOG.md (секция ## [Unreleased]).
#    ВЕРСИЮ НИГДЕ НЕ ПРАВИМ (issue #128): номер вычисляется из тегов.
#    Commit-префиксы влияют на bump: fix:/прочее → patch+1, feat: → minor+1,
#    BREAKING CHANGE/!: → major+1.

# 2. Примержить PR в main — CI сам вычислит следующий тег и соберёт GitHub Release
#    (branch protection: direct push в main невозможен, только PR)
```

---

## 📂 Project Structure (Full)

```
sms-forwarder/
├── app/
│   ├── build.gradle.kts           # App module config
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/ozyab/smsforwarder/
│   │   │   │   ├── SmsForwarderApp.kt      # Application: Prefs.init, Timber
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt     # 4 вкладки, тонкая склейка панелей
│   │   │   │   │   ├── ChannelsPanel.kt    # Панель «Каналы» (делегат)
│   │   │   │   │   ├── HistoryPanel.kt     # Панель «История» (делегат)
│   │   │   │   │   ├── MainViewModel.kt    # Состояние + операции
│   │   │   │   │   └── OnboardingActivity.kt
│   │   │   │   ├── receiver/
│   │   │   │   │   ├── SmsReceiver.kt
│   │   │   │   │   ├── CallReceiver.kt
│   │   │   │   │   └── BootReceiver.kt
│   │   │   │   ├── service/
│   │   │   │   │   ├── ForwardService.kt
│   │   │   │   │   ├── SendQueue.kt
│   │   │   │   │   └── EventQueueStore.kt
│   │   │   │   ├── telegram/
│   │   │   │   │   ├── Channel.kt          # Channel + ChannelStore
│   │   │   │   │   ├── ChannelSender.kt
│   │   │   │   │   ├── ChannelClientFactory.kt
│   │   │   │   │   └── TelegramClient.kt
│   │   │   │   ├── history/
│   │   │   │   │   ├── EventEntity.kt / EventDao.kt / EventDatabase.kt
│   │   │   │   │   └── EventHistory.kt
│   │   │   │   ├── update/
│   │   │   │   │   ├── UpdateChecker.kt / UpdateManager.kt
│   │   │   │   │   └── DownloadReceiver.kt
│   │   │   │   └── util/
│   │   │   │       ├── Prefs.kt            # DataStore + EncryptedSharedPreferences
│   │   │   │       ├── LogStore.kt
│   │   │   │       ├── ThemeManager.kt
│   │   │   │       ├── QuietHours.kt
│   │   │   │       ├── SettingsBackup.kt
│   │   │   │       ├── TemplateFormatter.kt
│   │   │   │       ├── ContactNames.kt / SimInfo.kt
│   │   │   │       └── ReceiverExecutor.kt
│   │   │   └── res/
│   │   │       ├── layout/ (activity_main, activity_onboarding, onboarding_step_*, item_channel)
│   │   │       ├── menu/bottom_nav.xml
│   │   │       ├── values/strings.xml, colors.xml, themes.xml
│   │   │       ├── values-en/strings.xml
│   │   │       ├── mipmap-*/ic_launcher*.png  # adaptive icon
│   │   │       └── xml/ (notification channels, backup rules)
│   │   └── test/java/com/ozyab/smsforwarder/  # unit-тесты (JUnit + Robolectric)
│   └── proguard-rules.pro
├── gradle/
│   ├── libs.versions.toml         # Version catalog (AGP, Kotlin, deps, app version)
│   └── wrapper/gradle-wrapper.properties
├── .github/workflows/build.yml    # CI/CD (PR: debug+tests+lint; main: auto tag; tag: release)
├── docs/
│   ├── TECH_TASK.md               # Техническое задание (актуализировано)
│   ├── BRAINSTORM.md              # Архив идей (статусы — исторические)
│   └── screenshots/               # app.png для README
├── build.gradle.kts               # Root build script
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── ROADMAP.md                     # План работ + карта покрытия тестами
├── CHANGELOG.md
├── README.md                      # User-facing documentation
└── AGENTS.md                      # This file
```

---

## 🛠️ Common Development Tasks

### Add a new dependency
Edit `gradle/libs.versions.toml` → add to `[versions]` and `[libraries]` → use as `libs.xxx` in `app/build.gradle.kts`.

### Update Gradle / AGP / Kotlin
Update versions in `gradle/libs.versions.toml` → `./gradlew wrapper --gradle-version X.Y` if needed.

### Debug on device
```bash
./gradlew installDebug
adb logcat -s "SMSForwarder:*" "ForwardService:*" "TelegramClient:*"
```

### Generate signed APK locally
```bash
export KEYSTORE_BASE64=...
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Update app icon
Run the icon generation script (see `docs/TECH_TASK.md` §6) from project root:
```bash
python3 generate_icons.py logo_transparent.png
# Updates mipmap-* and adaptive icon xml
```

---

## ⚠️ Known Issues / Gotchas

| Issue | Workaround / Fix |
|-------|------------------|
| Branch protection на main | Все изменения — через PR (build+test+lint обязателен). Номер версии из тегов (#128) — bump руками не нужен |
| FGS-старт из PHONE_STATE на Android 12+ | `ForwardService.start` обёрнут в try/catch; при запрете событие сохраняется в файл очереди и уйдёт при следующем старте сервиса |
| Без `READ_CALL_LOG` номера пропущенных не приходят (Android 9+) | UI предупреждает: фича «пропущенные» требует разрешения «Журнал вызовов» |
| «Стоп» сервиса | Одна toggle-кнопка в UI (btn_service_toggle, состояние `Prefs.forwardingEnabled`): очередь отбрасывается + ресиверы не ставят новые события до «Запустить» |
| Vendor autostart (MIUI, EMUI, OneUI) | Onboarding shows vendor-specific instructions; `START_STICKY` helps but not 100% |
| Android 13+ notification permission | Not requested — channel is `IMPORTANCE_MIN`, user can disable in system settings |
| CallLog permission revoked on some OEMs | OFFHOOK-трекинг: без READ_CALL_LOG пропущенным считается RINGING→IDLE без OFFHOOK; принятые вызовы не пересылаются |
| SMS receiver order | No priority set — works alongside default SMS app; no `READ_SMS` needed for incoming |
| Проверка обновлений | Авто-проверка раз в сутки + ручная кнопка «Проверить обновления»; метка throttle ставится только при успешном ответе GitHub API (сбой сети не блокирует повторные проверки) |
| Тёмная тема | Все тексты/иконки используют цвета темы (`textColorPrimary/Secondary`, `colorControlNormal`) — хардкод чёрного недопустим в новых layout. Выбор темы: `Prefs.themeMode` + `ThemeManager.apply()` в onCreate каждой Activity |
| Акцентные цвета | 7 палитр (бирюзовый, зелёный, красный, синий, фиолетовый, оранжевый, серый); `ThemeManager.setAccentAndApply()` применяет overlay через `activity.theme.applyStyle()`. Выбор в ChipGroup во вкладке «О приложении» |
| OkHttp-клиенты | Кэшируются в `ChannelClientFactory` по конфигурации канала, `invalidate()` при изменении каналов; НЕ закрывать клиенты после использования (в отличие от старого кода с shutdown) |
| Room-история | `EventDatabase` версия 2 (`MIGRATION_1_2` — chatId/botUsername); `fallbackToDestructiveMigration` как страховка: история не критична, при сбое миграции она просто очищается |
| Node.js 20 deprecation в CI | Warning от `upload-artifact@v4`, `setup-gradle@v4`, `action-gh-release@v2` — они принудительно работают на Node 24; обновление до node24-версий (upload-artifact@v6, setup-gradle@v5+, gh-release v3) — отдельный PR. До 16.09.2026 Node 20 удалят с раннеров — тогда станет ошибкой |
| Шаблоны сообщений | Удалены (рефакторинг): пересылка всегда в стандартном формате (TemplateFormatter.DEFAULT_*) |
| Username бота в истории | Кэш `Prefs.botUsername` (обновляется при успешной проверке связи); если пусто — один `getMe` при первой успешной отправке |

---

## 📝 Changelog Format

`CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com):
```markdown
## [v1.2.0] - 2026-09-09
### Added
- New feature X
### Fixed
- Bug Y
### Changed
- Refactored Z
```

Update in same PR that bumps version.

---

## ⚠️ Tooling / Cache Note (важно для агентов)

В этой среде read- и edit-инструменты могут показывать **устаревшие shadow-копии** файлов:
their вывод может расходиться с реальным содержимым на диске (несколько «копий» на
один путь). **Единственный источник истины — файлы на диске.**

Правила при работе здесь:
- Изменения проверяйте ТОЛЬКО через bash: `sed -n 'a,bp' file`, `grep -n ...`, `wc -l`,
  а сравнение с базой — утилитой `diff -u <main-копия> <файл>` (не `git diff`).
- Если read-tool / edit-tool показывают состояние, не совпадающее с bash — **доверяйте
  диску (bash)** и перечитывайте через bash до принятия решений.
- При сломанном git (`/usr/bin/git` упирается в Xcode license, docker недоступен)
  не полагайтесь на `git diff`/`git status` — сверяйтесь через `diff -u` с содержимым
  ветки, скачанным через `gh api`.

---

## 🔗 Useful Links

- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Android Foreground Services](https://developer.android.com/guide/components/foreground-services)
- [OkHttp Proxy Auth](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-proxy-authenticator/)
- [EncryptedSharedPreferences](https://developer.android.com/topic/security/data/encrypted-shared-preferences)
- [SemVer](https://semver.org/)

---

_Updated: 2026-09-13_
