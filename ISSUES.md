# 🐞 ISSUES — найденные проблемы (ревью кода)

> Результат ревью кодовой базы от **2026-09-22** (версия 0.5.40).
> Пункты сгруппированы по приоритету: 🔴 высокий (влияет на пользователей/данные),
> 🟡 средний (баги/риски в частых сценариях), 🔵 низкий (мелочи, косметика).
> В конце — список проблем, уже осознанных и задокументированных (чтобы не «переоткрывать»).

---

## 🔴 Высокий приоритет

### 1. OutgoingSmsObserver: тяжёлая работа на главном потоке — риск ANR
**Где:** `service/OutgoingSmsObserver.kt`, `onChange()`

Observer регистрируется с `Handler(Looper.getMainLooper())`, поэтому `onChange()`
выполняется на main thread — и прямо там делает запросы к `content://sms`
(`readLastSentSms()`, `getLastSentSmsId()`), `ContactNames.lookup()` (ещё один
запрос к провайдеру контактов) и форматирование. `SmsReceiver` для того же
выносит работу в фон через `ReceiverExecutor.goAsync()`, а observer — нет.

**Эффект:** пачка изменений SMS-провайдера (исходящая пачка, синхронизация) →
блокировка main thread → ANR/подлагивания UI.

**Рекомендация:** вынести чтение провайдера и формирование события в фоновый
поток (тот же `ReceiverExecutor` или свой executor), в `onChange()` только
детект изменения.

---

### 2. Prefs.awaitReady() блокирует поток до 5 секунд, в т.ч. главный
**Где:** `util/Prefs.kt`, `awaitReady()`; вызывается из каждого аксессора

Аксессоры `Prefs` вызываются на main thread из ресиверов (`SmsReceiver.onReceive`
→ `Prefs.smsEnabled`, `CallReceiver.onReceive`, `BootReceiver`), из
`MainActivity.onCreate`, `QuietHours.isActiveNow()` в ресиверах и т.д. При
медленной инициализации (DataStore миграция, EncryptedSharedPreferences на
слабом устройстве) поток блокируется до 5 секунд.

**Эффект:** worst case — ANR в `onReceive` (лимит ~10 c для fg-броадкастов) и
зависание холодного старта UI. Обычный случай — десятки мс, но защита
«страховочный таймаут» именно допускает 5-секундную блокировку.

**Рекомендация:** в ресиверах читать настройки уже внутри `ReceiverExecutor.goAsync()`
(там это почти бесплатно — работа и так в фоне), а быстрой проверки «включено ли»
на main thread держать лёгкий in-memory флаг, обновляемый из коллектора DataStore.

---

### 3. CallReceiverLogic: RINGING-броадкаст без номера затирает запомненный номер
**Где:** `receiver/CallReceiver.kt`, `CallReceiverLogic.onPhoneStateChanged()`, ветка `EXTRA_STATE_RINGING`

```kotlin
TelephonyManager.EXTRA_STATE_RINGING -> {
    ringingNumber = number   // number может быть null!
    ...
}
```

На многих устройствах (dual-SIM, ряд OEM) `PHONE_STATE` приходит по несколько раз
на каждое изменение состояния, причём `EXTRA_INCOMING_NUMBER` есть только в части
броадкастов. Повторный RINGING без номера перетирает ранее запомненный номер.

**Эффект:** пропущенный вызов без номера; fallback на `findRecentMissed()` спасает
только при наличии `READ_CALL_LOG` — без него событие теряется совсем.

**Рекомендация:** перезаписывать только непустым значением:
`if (!number.isNullOrBlank()) ringingNumber = number`.

---

### 4. Гонка EventQueueStore: снимок очереди (saveAsync) перезаписывает события persistSingle
**Где:** `service/EventQueueStore.kt` (`saveAsync` vs `persistSingle`), сценарий
`ForwardService.start(context, text, …)` catch-ветки

Когда FGS не удалось стартовать из фона (Android 12+), событие добавляется в файл
через `persistSingle()`. Параллельно живой сервис может писать снимок очереди
через `saveAsync()` — снимок берётся из памяти и **не содержит** событие,
добавленное `persistSingle`, после чего атомарно перезаписывает файл.

Executor один (запись сериализована), но семантика «файл = память ∪ persistSingle»
не соблюдается: последний `saveAsync` затирает события, добавленные напрямую в файл.
Аналогичный риск при `clear()` (ACTION_STOP) между записью и чтением.

**Эффект:** тихая потеря событий в редком, но именно для того и спроектированном
сценарии («сервис недоступен — сохраним на диск»).

**Рекомендация:** либо `persistSingle` читать-мержить-писать под общим локом с
`snapshots` (хранить pendingSave + последние persistSingle-события и мержить при
записи), либо после удачного `load()` в старте сервиса всегда ребейзить снимок от файла, а не от памяти.

---

### 5. Автообновление может скачать APK чужой архитектуры
**Где:** `update/UpdateChecker.check()` + `app/build.gradle.kts` (splits) + CI (`files: app/build/outputs/apk/release/*.apk`)

В релизе с `splits.abi` два APK: `app-arm64-v8a-release.apk` и
`app-armeabi-v7a-release.apk` (universal отключён). `UpdateChecker` берёт
**первый попавшийся** ассет с расширением `.apk` — порядок ассетов на GitHub
Release произволен. Никакой проверки `Build.SUPPORTED_ABIS` нет.

**Эффект:** на arm64-устройство может скачаться armeabi-v7a APK (работает, но
неоптимально) — или, в перспективе добавления других ABI, вовсе нерабочий.

**Рекомендация:** выбирать ассет по имени (`arm64-v8a`/`armeabi-v7a`) исходя из
`Build.SUPPORTED_ABIS`, либо собирать отдельный universal APK только для
автообновления.

---

### 6. Локальные уведомления не работают на Android 13+ (POST_NOTIFICATIONS не запрашивается)
**Где:** `util/LocalNotifier.kt`, `MainActivity.requestNeededPermissions()`

Разрешение `POST_NOTIFICATIONS` сознательно не запрашивается — обоснование в коде
относится к FGS-уведомлению (IMPORTANCE_MIN, «невидимое»). Но `LocalNotifier` —
пользовательская фича («уведомлять о SMS/звонках на устройстве»), и на Android 13+
без разрешения она молча ничего не показывает, даже при включённом тумблере.

**Эффект:** заявленная фича не работает на Android 13+ по умолчанию; пользователь
не получает ни ошибки, ни подсказки.

**Рекомендация:** запрашивать `POST_NOTIFICATIONS` в момент включения тумблера
локальных уведомлений (или показывать пояснение с переходом в настройки канала).

---

### 7. FGS-тип `dataSync` остановится по 6-часовому лимиту при targetSdk 35+ (Android 15+)
**Где:** `service/ForwardService.kt` (`startAsForeground`), `AndroidManifest.xml` (`foregroundServiceType="dataSync"`), `libs.versions.toml` (targetSdk 34)

Приложение живёт как постоянный dataSync FGS с `START_STICKY`. На Android 15+
для приложений с targetSdk 35+ действует лимит: `dataSync` FGS суммарно не более
6 часов в 24-часовом окне, после чего система его останавливает и не даёт
перезапустить тот же тип до следующего окна. Сейчас targetSdk 34 — лимит ещё не
применяется, но поднятие targetSdk (неизбежно: политика Play/Android 16) включит
его, и пересылка начнёт молча умирать раз в сутки.

**Рекомендация:** к моменту bump targetSdk до 35+ перейти на
`FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (с обоснованием в манифесте) — для
«постоянной пересылки» это штатный выход; заложить в план заранее.

---

## 🟡 Средний приоритет

### 8. ChannelStore: несинхронизированный read-modify-write
**Где:** `telegram/Channel.kt` (`ChannelStore`)

`promote()`/`demote()` вызываются из воркера сервиса (Dispatchers.IO), правки
каналов — из UI (main thread). Оба пути делают `all()` → модификация → `setAll()`
без общего лока; `cache` лишь `@Volatile`. Возможна потерянная правка
(например, promote затирает только что добавленный пользователем канал).

**Рекомендация:** `@Synchronized` на мутациях `ChannelStore` (или единый `lock`
вокруг read-modify-write).

### 9. Prefs: узкое окно отката кэша между updateCache() и pendingWrites.incrementAndGet()
**Где:** `util/Prefs.kt` (`setString`/`setBoolean`/… + `persist`)

Механизм `pendingWrites` чинит гонку с коллектором DataStore, но инкремент
счётчика происходит в `persist()` **после** `updateCache()`. Между этими двумя
шагами эмиссия DataStore может закэшировать старый снимок. Окно микросекундное,
но это тот же класс флейков, что уже чинился.

**Рекомендация:** инкрементировать `pendingWrites` до `updateCache()`.

### 10. Экспорт логов рапортует успех даже при незаписанном файле
**Где:** `ui/MainActivity.kt`, `writeLogsToUri()`

`contentResolver.openOutputStream(uri)?.use { … }` — если поток вернулся `null`,
функция всё равно возвращает `true` («экспорт выполнен»), ничего не записав.

**Рекомендация:** различать `null`-поток и вернуть `false`.

### 11. Поиск по истории: `%` и `_` работают как wildcard
**Где:** `history/EventDao.kt` (`search`)

Пользовательский запрос подставляется в `LIKE '%' || :query || '%'` без
экранирования — `%`/`_` в поиске дают неожиданные результаты (не инъекция,
параметризация есть, но UX поиска «плавает»).

**Рекомендация:** экранировать `%`/`_`/`\` и использовать `ESCAPE '\'`.

### 12. 429 (retry_after) от Bot API трактуется как обычная ошибка канала
**Где:** `telegram/ChannelSender.kt` (`realSender`)

Telegram возвращает 429 с `parameters.retry_after` при флуд-лимите. Сейчас это
просто `ChannelOutcome.Failed(desc)`: healthy канал понижается (demote), а
повтор происходит по общему бэк-оффу, который может быть меньше рекомендованного
`retry_after`.

**Рекомендация:** парсить `retry_after` из ответа и учитывать при планировании
ретрая события (хотя бы логировать явно).

### 13. SIM-атрибуция исходящих SMS всегда «первая SIM»
**Где:** `util/SimInfo.kt` + `service/OutgoingSmsObserver.kt`

Для исходящих SMS subscriptionId недоступен, `SimInfo.describe(context, null)`
берёт первую активную подписку. Сообщение, отправленное со второй SIM,
подписывается в Telegram как «Sim1 <оператор>».

**Рекомендация:** для исходящих попытаться вытащить subscriptionId из тела/URI
отдельного сообщения (у Telephony.Sms есть колонка `sub_id` — читать её в
`readLastSentSms()`), или не показывать SIM вовсе.

### 14. Исходящие SMS: пропуски из-за гонки статуса и сброса lastSeenId
**Где:** `service/OutgoingSmsObserver.kt`

Два известных пропуска:
- ContentObserver срабатывает сразу после вставки строки, когда `type` ещё
  `QUEUED/OUTBOX`; `readLastSentSms()` возвращает `null`, `lastSeenId` не
  обновился — но и повторного onChange может не быть (тип меняется без нотификации
  в ряде прошивок) → сообщение не переслано.
- Пока сервис мёртв, отправленные SMS не пересылаются никогда: при `start()`
  `lastSeenId` сбрасывается на текущий последний — «навёрстывать» ничего не нужно
  по дизайну, но пользователь может ожидать догонку после рестарта.

**Рекомендация:** для первого — повторная проверка через задержку (debounce +
отложенный re-read); для второго — осознанно зафиксировать в доке/README.

### 15. renderHistory(): запрос Room на каждый вводимый символ
**Где:** `ui/MainActivity.kt` (`etHistorySearch.addTextChangedListener`)

Нет debounce — при каждом символеlaunch корутины и запрос к Room. На длинной
истории (лимит 1000) — заметные подлагивания. Плюс `EventHistory.pruneOld()`
читает `recent(1001)` при каждой вставке события.

**Рекомендация:** debounce 300 мс для поиска; pruneOld заменить на периодическую
обрезку или `DELETE FROM events WHERE id NOT IN (SELECT id … ORDER BY timestamp DESC LIMIT 1000)`.

### 16. Счётчики отброшенных событий SendQueue нигде не видны
**Где:** `service/SendQueue.kt` (`droppedAfterAttempts`, `droppedOverflow`)

При переполнении очереди (100) самое старое событие молча отбрасывается — ни
записи в `LogStore`, ни в историю (`dropped` пишется только при исчерпании
попыток). Пользователь не узнает о потере.

**Рекомендация:** логировать/писать историю при overflow-отбрасывании.

### 17. Тихие часы: start == end молча означает «выключено»
**Где:** `util/QuietHours.kt` (`isActive`) + UI

Равные start/end трактуется как пустой интервал. Пользователь, выставивший
одинаковое время (ожидая «всегда тихо» или просто ошибившись), не получает
никакого предупреждения.

**Рекомендация:** валидация/подсказка в UI (и/или явная трактовка в доке).

### 18. Фильтр «Звонки» в истории показывает только пропущенные
**Где:** `ui/MainActivity.kt` (`renderHistory()`: `chip_history_calls → TYPE_MISSED`)

Входящие и исходящие звонки (types `incoming`/`outgoing`) недоступны ни через
один чип фильтра — только «Все», «SMS», «Звонки (missed)».

**Рекомендация:** расширить запрос фильтра на `type IN ('missed','incoming','outgoing')`.

### 19. ChatIdFailed автоматически открывает t.me без действия пользователя
**Где:** `ui/MainActivity.kt` (`handleUiEvent`)

При неудаче определения Chat ID сразу стартуется `ACTION_VIEW` на
`https://t.me/<bot>` — неожиданный уход из приложения.

**Рекомендация:** показывать ссылку в диалоге/тосте с кнопкой, а не открывать сразу.

### 20. Установка скачанного APK: нет подсказки про «неизвестные источники»
**Где:** `update/DownloadReceiver.kt`, `update/UpdateManager.kt`

Есть разрешение `REQUEST_INSTALL_PACKAGES`, но на устройстве, где не выдано
«Установка приложений из этого источника», интент установки просто не сработает —
без диагностики и подсказки, куда идти.

**Рекомендация:** перед установкой проверять
`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` и предлагать выдать разрешение.

---

## 🔵 Низкий приоритет / косметика

### 21. Заголовок локального уведомления о звонке
`receiver/CallReceiver.kt`: `title = "📵 $label: $number"` — эмодзи пропущенного
для всех типов (в т.ч. входящий/исходящий), а `number` берётся из интента (может
быть `null` → «null» в заголовке), хотя в `result` уже есть resolved-номер.

### 22. subId == 0 отбрасывается как невалидный
`receiver/SmsReceiver.kt`: `getIntExtra("subscription", -1).takeIf { it > 0 }` —
валидный `subId = 0` (INVALID = -1) на части устройств/эмуляторов теряется.
Корректнее `takeIf { it != -1 }`.

### 23. Мёртвое ProGuard-правило TDLib
`app/proguard-rules.pro`: `-keep class org.drinkless.tdlib.** { *; }` — TDLib в
проекте нет (правило осталось от ранней версии). Вводит в заблуждение.

### 24. Устаревший комментарий в build.gradle.kts
`app/build.gradle.kts`: «релизные сборки только в GitLab CI» — CI давно GitHub
Actions (см. AGENTS.md / build.yml).

### 25. Ошибочный KDoc у ThemeManager.apply
`util/ThemeManager.kt`: «накладывает стиль акцента через
`AppCompatDelegate.setDefaultNightMode`» — копипаста, акцент накладывается через
`theme.applyStyle()`.

### 26. Пустой override onDestroy в MainActivity
`ui/MainActivity.kt` — только `super.onDestroy()`, можно удалить.

### 27. Устаревшие зависимости
`gradle/libs.versions.toml`: core-ktx 1.13.1, activity-ktx 1.9.3, appcompat
1.7.0, lifecycle 2.8.3, robolectric 4.13 — доступны заметно более новые.
(Deprecated `security-crypto` — осознанно, см. ниже.)

### 28. OnboardingActivity: scope без SupervisorJob
`ui/OnboardingActivity.kt`: `CoroutineScope(Dispatchers.Main)` — падение одной
корутины убивает весь scope (кнопка «Определить ID» перестанет работать до
пересоздания Activity). Плюс токен сохраняется в Prefs уже на шаге «Далее» —
частично заполненный онбординг оставляет токен в хранилище.

### 29. EventQueueStore.load() глотает ошибку битого файла без лога
`service/EventQueueStore.kt`: `catch (e: Exception) { emptyList() }` — диагностика
потери очереди затруднена. Стоит хотя бы `LogStore.warn`.

### 30. compareVersions не понимает prerelease-теги
`update/UpdateChecker.kt`: `0.6.0-rc1` парсится как `0.6.0` (нецифровые части
отбрасываются) — prerelease будет считаться равным релизу. Не критично, но при
появлении rc-релизов сравнение врёт.

---

## ✅ Уже известные/осознанные (не баги, зафиксировано в коде/доках)

- **`security-crypto` deprecated** — миграция осознанно отложена ради совместимости
  данных пользователей (см. шапку `Prefs.kt`).
- **Plaintext-fallback секретов** при сломанном AndroidKeyStore
  (`secure_prefs_fallback`) — осознанный trade-off «лучше работать, чем терять токен».
- **ReceiverExecutor: один поток для всех ресиверов** — head-of-line blocking
  между SMS/звонками осознан, т.к. критичен порядок RINGING→OFFHOOK→IDLE.
- **Robolectric-дыры**: `ForwardServiceTest`, `EventDaoTest` — `@Ignore`
  (ограничения Robolectric), см. ROADMAP/AGENTS.
- **Токен/chatId сохраняются на каждый onPause** — намеренное «сохраняем ввод».
- **Node.js 20 deprecation warnings в CI** — известная косметика, обновление
  экшенов отложено отдельным PR.
- **«Стоп» сервиса отбрасывает очередь** — задокументированное поведение.

---

## Итоговая статистика

| Приоритет | Кол-во | Ключевые темы |
|-----------|--------|---------------|
| 🔴 Высокий | 7 | main-thread блокировки/ANR, потеря событий и номера, автoupdate ABI, POST_NOTIFICATIONS, dataSync-лимит Android 15 |
| 🟡 Средний | 13 | гонки хранилищ, UX-дыры фильтров/тихих часов, 429, поиск, перфоманс |
| 🔵 Низкий | 10 | косметика, мёртвый код, устаревшие зависимости, док-комментарии |

Рекомендуемый порядок работ: №1–№3 (ANR/потери) → №5, №6 (фичи, которые «не
работают» у части пользователей) → №4, №8 (гонки) → далее по мере сил.
