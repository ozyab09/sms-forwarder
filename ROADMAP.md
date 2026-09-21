# 🗺️ ROADMAP — SMS Forwarder (Android → Telegram)

> Живой документ: дорожная карта приложения. Обновляется по мере выполнения.
> Соглашения: каждый пункт — issue + feature-ветка + PR → зелёный CI (build + тесты + lint) → squash-мерж. Версию bump'ит **автор в том же PR** (auto-bump ботом убран: branch protection запрещает прямые push в main, #95); после мержа CI создаёт тег v0.5.X и GitHub Release.

**Текущая версия:** 0.5.40 · **Дата:** 2026-09-21

---

## ✅ Реализовано (история)

- [x] **SMS-пересылка** — `SmsReceiver` → `ForwardService`, имя контакта, формат по шаблону.
- [x] **Пропущенные вызовы** — `CallReceiver` (READ_PHONE_STATE + READ_CALL_LOG).
- [x] **Токен бота** — ввод-пароль в UI, `EncryptedSharedPreferences` (AES-256).
- [x] **Chat ID** — авто-определение через getUpdates («Получить мой ID»).
- [x] **Каналы отправки** — «Без прокси» + N HTTP/SOCKS5 прокси; порядок = приоритет каскада.
- [x] **Каскадные ретраи** — эксп. бэкофф 15с→30с→…→10 мин, кап 8 попыток, promote-on-success (успешный канал — первым).
- [x] **Foreground Service** — START_STICKY, автозапуск после перезагрузки (BootReceiver), без heads-up уведомлений.
- [x] **Тихие часы** — интервалы в т.ч. через полночь (23:00–08:00), в Prefs минутами от полуночи.
- [x] **История событий** — Room (EventDao/EventEntity/EventHistory), вкладка «История».
- [x] **Детали события в истории** — диалог по клику: кому (Chat ID), через какого бота, канал, попытки, полный текст отправленного сообщения (Room v2: chatId/botUsername). ✅ 2026-09-13
- [x] **Экспорт/импорт настроек** — SAF JSON, privacy-first (токен/пароли НЕ пишутся).
- [x] **Шаблоны сообщений** — `{sender} {name} {text} {time} {date} {type} {sim} {number}`.
- [x] **Превью и управление шаблонами** — «Проверить» (диалог: как уйдёт SMS и пропущенный вызов), «Сохранить», «Сбросить» (пусто = стандартный формат). ✅ 2026-09-13
- [x] **Логи** — вкладка «Логи», кольцевой буфер 200 записей, уровни INFO/OK/WARN/ERROR.
- [x] **Автообновления** — проверка GitHub Releases при запуске (throttle 24ч), ручная кнопка, DownloadManager.
- [x] **Темы** — светлая / тёмная / по системе.
- [x] **MVVM** — `MainViewModel` (StateFlow+SharedFlow), MainActivity — тонкий вид (PR #56/#57), поворот экрана безопасен (#59). Issue #55 закрыт.
- [x] [2026-09-13] **Фикс гонки `Prefs`** — коллектор DataStore больше не откатывает оптимистичный кэш (флейки теста `save`).
- [x] **Тесты** — 10 файлов: TemplateFormatter, QuietHours, ChannelStore (promote), UpdateChecker (версии/links), SendQueue, ChannelSender, ChannelClientFactory, EventDao, MainActivityLaunch (6 UI), UtilTest. + [2026-09-13] LogStoreTest, SettingsBackupTest, UI-тест версии в «О приложении».
- [x] **CI/CD** — GitHub Actions: PR → debug + unit-тесты + lintDebug; push в main → тег v0.5.X по версии из `libs.versions.toml` (без bump-коммита, #95); тег → unit-тесты + подписанный release APK + GitHub Release (notes из CHANGELOG.md). Branch protection на main: всё через PR.
- [x] **README** — приведён к актуальному состоянию (2026-09-13): тихие часы, история, экспорт/импорт, шаблоны, MVVM-структура, статусы идей.
- [x] **Документация** — [2026-09-13] TECH_TASK/AGENTS/BRAINSTORM/README/screenshots актуализированы, T6 закрыт (§ выше).
- [x] [2026-09-14] **Починка релизного конвейера** — усечённый `sendCascade` восстановлен (#90), остатки F10 вычищены, lintDebug в PR + тесты перед релизом (#91), auto-tag без бота-пуша (#95). Релизы v0.5.34/v0.5.35 зелёные.
- [x] **Исходящие SMS** — `ContentObserver` на `content://sms/sent` (READ_SMS). ✅ 2026-09-14
- [x] **Принятые входящие звонки** — `RINGING → OFFHOOK → IDLE` → `type = "incoming"`. ✅ 2026-09-14
- [x] **Исходящие звонки** — `OFFHOOK без RINGING → IDLE` → `type = "outgoing"`. ✅ 2026-09-14
- [x] **Динамическая сортировка каналов (#123)** — demote-on-failure (неудачный канал — в конец, в т.ч. «Без прокси»), promote-on-success расширен на direct, авто-отключение каналов исключено регрессионными тестами. ✅ 2026-09-21

---

## 🚧 План работ (по приоритету)

### P0 — критичные тестовые дыры (без них рискован рефакторинг)

- [x] **T1. MainViewModelTest** — `load()` / `save()` / `testConnection()` / `resolveChatId()`, разбор событий SharedFlow, отсутствие токена → ToastRes, ошибки каналов. ✅ 2026-09-13 (`MainViewModelTest`, 10 сценариев; инжекция зависимостей + `ioDispatcher`).
- [x] **T2. TelegramClientTest** — OkHttp MockWebServer: sendMessage/getUpdates/getMe (успех/ошибка), пустые токен и chat id, проверка пути и тела запроса. ✅ 2026-09-13 (`TelegramClientTest`, 10 тестов).

### P1 — тесты инфраструктуры

- [x] **T3. EventQueueStoreTest** — load/save/clear/persistSingle, атомарная запись (tmp+rename без остатков), битый файл → пустая очередь, лимит MAX_EVENTS, пустые тексты пропускаются, сброс nextRetryAt. ✅ 2026-09-13 (`EventQueueStoreTest`, 9 тестов).
- [x] **T4. ForwardService интеграционный** — Robolectric ServiceTestRule: enqueue → pollReady → retry → promote; не теряет очередь при рестарте. ✅ 2026-09-13 (`ForwardServiceTest`, 8 тестов).
- [x] **T5. SmsReceiver / CallReceiver** — событие → формат → очередь; тихие часы уважаются; короткие номера/пустые тексты. ✅ 2026-09-13 (`ReceiverTest`, 14 тестов: state machine + guards).

### P2 — мелкие дыры по ТЗ и UX

- [x] **T6. ТЗ: «Фильтр коротких номеров»** — фича удалена в #50; решение зафиксировано в ТЗ (§3.1/§3.3 — помечено «убрано, заменено шаблонами/тихими часами»). ✅ 2026-09-13 (+ актуализация TECH_TASK: minSdk 29, каналы, GitHub Actions; BRAINSTORM/AGENTS/README приведены к факту).
- [x] **T7. UI-тест «нет heads-up уведомления»** — канал `IMPORTANCE_MIN` (без звука/вибрации/бейджа), уведомление `PRIORITY_MIN` + ongoing, ни одного уведомления HIGH/MAX. ✅ 2026-09-13 (`ForwardServiceNotificationTest`, 3 теста).

### P3 — новые функции (после закрытия тестовых дыр)

- [x] **F1. Поиск/фильтр по логам** — вкладка «Логи»: фильтр по уровню (чипы) и тексту (поиск). ✅ 2026-09-13
- [x] **F2. Экспорт логов в файл** — SAF, текстовый формат [HH:mm:ss] [LEVEL] text. ✅ 2026-09-13
- [x] **F3. Локальные уведомления** — опция «Уведомления на телефоне»: уведомляет о входящих SMS/звонках на устройстве (отдельный канал с IMPORTANCE_DEFAULT). ✅ 2026-09-13
- [x] **Акцентные цвета** — 7 акцентных палитр (бирюзовый, зелёный, красный, синий, фиолетовый, оранжевый, серый); выбор во вкладке «О приложении». ✅ 2026-09-13
- [x] **Исходящие SMS** — `ContentObserver` на `content://sms/sent` (READ_SMS). ✅ 2026-09-14
- [x] **Принятые входящие звонки** — `RINGING → OFFHOOK → IDLE` → `type = "incoming"`. ✅ 2026-09-14
- [x] **Исходящие звонки** — `OFFHOOK без RINGING → IDLE` → `type = "outgoing"`. ✅ 2026-09-14

### 📝 Удалено (не работало / избыточно)
- **F5. Дублирование каналов** — не работало (нет способа настроить разные Chat ID для разных каналов).
- **F10. Пересылка уведомлений** — выбор приложений не работал.
- **F4. Мульти-бот** — не планируется.
- **F6. Regex-фильтры** — не планируется.
- **F7. Правила пересылки** — не планируется.
- **F8. Webhook (HTTP POST)** — не планируется.
- **F9. Email (SMTP)** — не планируется.

---

## 📌 Как выполняется пункт

1. Issue на GitHub + ветка `feat/<краткое-имя>` от свежего main.
2. Код/тесты → локальный прогон `./gradlew testDebugUnitTest` (нужен Android SDK:
   `ANDROID_HOME` или `local.properties`; без SDK проверка — CI).
3. PR → CI (build + тесты + lint) зелёный → squash-мерж.
4. В том же PR: patch-bump в `gradle/libs.versions.toml` + запись в `CHANGELOG.md`.
   После мержа в main CI сам создаёт тег `vX.Y.Z` и GitHub Release с APK.
5. Обновить документацию (этот файл, README, AGENTS, при необходимости TECH_TASK) — отметить выполненное.

---

## 📊 Карта покрытия тестами (2026-09-14, ~155 тестов; 16 пропускаются — @Ignore)

| Класс | Тест | Статус |
|-------|------|--------|
| TemplateFormatter | TemplateFormatterTest (21: плейсхолдеры + превью + новые типы) | ✅ |
| QuietHours | QuietHoursTest (7) | ✅ |
| ChannelStore (promote) | ChannelStoreTest (7) | ✅ |
| SendQueue | SendQueueTest (9) | ✅ |
| ChannelSender | ChannelSenderTest (9: каскад, параллельный testAll) | ✅ |
| ChannelClientFactory | ChannelClientFactoryTest (6) | ✅ |
| UpdateChecker (версии/links) | UtilTest (9) | ✅ |
| MainActivity (UI) | MainActivityLaunchTest (7): старт/холодный старт/поворот/табы/версия | ✅ |
| LogStore | LogStoreTest (5) | ✅ (2026-09-13) |
| SettingsBackup | SettingsBackupTest (11: новые настройки + шаблоны + звонки) | ✅ (2026-09-14) |
| MainViewModel | MainViewModelTest (9) | ✅ (2026-09-13) |
| TelegramClient | TelegramClientTest (10, MockWebServer) | ✅ (2026-09-13) |
| EventQueueStore | EventQueueStoreTest (9) | ✅ (2026-09-13) |
| SmsReceiver/CallReceiver | ReceiverTest (18: state machine + guards) | ✅ (2026-09-14) |
| FGS-уведомление | ForwardServiceNotificationTest (3) | ✅ (2026-09-13) |
| ForwardService | ForwardServiceTest (8) | ⏸️ @Ignore — Robolectric не отменяет корутину сервиса (нужен TestDispatcher) |
| EventDao (Room) | EventDaoTest (8) | ⏸️ @Ignore — Robolectric+Room ClassNotFoundException (нужен robolectric-sqlite или перенос в androidTest) |