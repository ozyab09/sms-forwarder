# 🗺️ ROADMAP — SMS Forwarder (Android → Telegram)

> Живой документ: дорожная карта приложения. Обновляется по мере выполнения.
> Соглашения: каждый пункт — feature-ветка + PR → зелёный CI → авто-мерж (squash) → bump версии (patch) + changelog + release.

**Текущая версия:** 0.4.26 · **Дата:** 2026-09-13

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
- [x] **Экспорт/импорт настроек** — SAF JSON, privacy-first (токен/пароли НЕ пишутся).
- [x] **Шаблоны сообщений** — `{sender} {name} {text} {time} {date} {type} {sim} {number}`.
- [x] **Логи** — вкладка «Логи», кольцевой буфер 200 записей, уровни INFO/OK/WARN/ERROR.
- [x] **Автообновления** — проверка GitHub Releases при запуске (throttle 24ч), ручная кнопка, DownloadManager.
- [x] **Темы** — светлая / тёмная / по системе.
- [x] **MVVM** — `MainViewModel` (StateFlow+SharedFlow), MainActivity — тонкий вид (PR #56/#57), поворот экрана безопасен (#59). Issue #55 закрыт.
- [x] **Тесты** — 10 файлов: TemplateFormatter, QuietHours, ChannelStore (promote), UpdateChecker (версии/links), SendQueue, ChannelSender, ChannelClientFactory, EventDao, MainActivityLaunch (6 UI), UtilTest. + [2026-09-13] LogStoreTest, SettingsBackupTest, UI-тест версии в «О приложении».
- [x] **CI/CD** — GitHub Actions: PR → debug+тесты+lint; тег vX.Y.Z → подписанный release APK + GitHub Release.
- [x] **README** — приведён к актуальному состоянию (2026-09-13): тихие часы, история, экспорт/импорт, шаблоны, MVVM-структура, статусы идей.

---

## 🚧 План работ (по приоритету)

### P0 — критичные тестовые дыры (без них рискован рефакторинг)

- [ ] **T1. MainViewModelTest** — `load()` / `save()` / `testConnection()` / `resolveChatId()`, разбор событий SharedFlow, отсутствие токена → ToastRes, ошибки каналов.
- [ ] **T2. TelegramClientTest** — OkHttp MockWebServer: getMe/sendMessage (успех/ошибка/таймаут), базовый auth, прокси-конфиг (IP/порт в URL).

### P1 — тесты инфраструктуры

- [ ] **T3. EventQueueStoreTest** — load/save/clear/persistSingle, атомарная запись (tmp+rename), битый файл → пустая очередь, лимит MAX_EVENTS.
- [ ] **T4. ForwardService интеграционный** — Robolectric ServiceTestRule: enqueue → pollReady → retry → promote; не теряет очередь при рестарте.
- [ ] **T5. SmsReceiver / CallReceiver** — событие → формат → очередь; тихие часы уважаются; короткие номера/пустые тексты.

### P2 — мелкие дыры по ТЗ и UX

- [ ] **T6. ТЗ: «Фильтр коротких номеров»** — фича удалена в #50; зафиксировать решение в ТЗ (§3.3) — пункт помечен «убрано, заменено шаблонами/тихими часами».
- [ ] **T7. UI-тест «нет heads-up уведомления»** — проверить, что сервис создаёт уведомление с низким приоритетом (не всплывает) и не создаёт обычных.

### P3 — новые функции (после закрытия тестовых дыр)

- [ ] **F1. Поиск/фильтр по логам** — вкладка «Логи»: фильтр по уровню и тексту (сейчас 200 записей вручную прокручивать).
- [ ] **F2. Экспорт логов в файл** — SAF, по паттерну SettingsBackup (JSON/txt).
- [ ] **F3. Статистика** — Room-запросы: SMS/день, успешность по каналам, latency; простая вкладка/карточки.
- [ ] **F4. Локальные уведомления** — (опция) уведомлять на телефоне о приходе SMS, даже если бот недоступен.
- [ ] **F5. Мульти-бот** — несколько токенов/Chat ID (разные получатели) — рефакторинг Prefs/ChannelStore.
- [ ] **F6. Дублирование каналов** — параллельная отправка в 2+ бота (не каскадом).
- [ ] **F7. F-Droid** — reproducible builds для публикации.

### 🔮 Идеи из README (не приоритетные)

- Web UI для логов (NanoHTTPD, локальная сеть).
- MQTT/Webhook каналы (Home Assistant, n8n).
- Kotlin Multiplatform (iOS).

---

## 📌 Как выполняется пункт

1. Ветка `feat/<краткое-имя>` от свежего main.
2. Код/тесты → локальный прогон `./gradlew testDebugUnitTest` (JAVA_HOME=/opt/jdk/jdk-17.0.20.1+1).
3. PR → CI (тесты+lint) зелёный → squash-мерж (правило: зелёный МР = авто-мерж без ожидания).
4. Bump: patch в `gradle/libs.versions.toml` + CHANGELOG.md + ветка `release/vX.Y.Z` → PR → мерж → автотег → GitHub Release с APK.
5. Обновить этот файл (отметить выполненное).

---

## 📊 Карта покрытия тестами (2026-09-13)

| Класс | Тест | Статус |
|-------|------|--------|
| TemplateFormatter | TemplateFormatterTest (9) | ✅ |
| QuietHours | QuietHoursTest (7) | ✅ |
| ChannelStore (promote) | ChannelStoreTest (7) | ✅ |
| SendQueue | SendQueueTest | ✅ |
| ChannelSender | ChannelSenderTest (16) | ✅ |
| ChannelClientFactory | ChannelClientFactoryTest | ✅ |
| UpdateChecker (версии/links) | UtilTest (8) | ✅ |
| EventDao (Room) | EventDaoTest | ✅ |
| MainActivity (UI) | MainActivityLaunchTest (6): старт/холодный старт/поворот/табы/версия | ✅ |
| LogStore | LogStoreTest (6) | ✅ (2026-09-13) |
| SettingsBackup | SettingsBackupTest (8) | ✅ (2026-09-13) |
| MainViewModel | — | ❌ T1 |
| TelegramClient | — | ❌ T2 |
| EventQueueStore | — | ❌ T3 |
| ForwardService | — | ❌ T4 |
| SmsReceiver/CallReceiver | — | ❌ T5 |