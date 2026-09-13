# 📱 SMS Forwarder — Android → Telegram

> Пересылает входящие SMS и пропущенные вызовы с Android в Telegram.
> Работает в фоне **без всплывающих уведомлений**, не выгружается системой.
> Поддержка HTTP/SOCKS5 прокси для обхода блокировок.

![android](https://img.shields.io/badge/Android-10.0%2B-green)
![kotlin](https://img.shields.io/badge/Kotlin-2.2-orange)
![license](https://img.shields.io/badge/license-MIT-lightgrey)

---

## 📸 Скриншот

![SMS Forwarder](docs/screenshots/app.png)

---

## 🚀 Возможности

- 📩 **SMS** — мгновенно, с именем отправителя из контактов
- 📵 **Пропущенные вызовы** — только реально не отвеченные
- 🚫 **Без всплывающих уведомлений** — невидимый канал, не мешает
- 🌙 **Работает в фоне** — автозапуск после перезагрузки, защита от выгрузки (Doze/Exemption)
- 🔒 **Токен в приложении** — вводите в UI, хранится в EncryptedSharedPreferences (AES-256)
- 🌐 **Многоканальная отправка** — «Без прокси» + неограниченное число HTTP/SOCKS5 прокси
- 🔄 **Каскадные ретраи** — пробует каналы по порядку, экспоненциальный бэкофф 15с→30с→60с (кап 10 мин), суммарно ~15 мин; успешный канал автоматически становится приоритетным
- ⏰ **Тихие часы** — расписание, когда события не отправляются (может пересекать полночь)
- 📜 **История событий** — вкладка с сохранёнными SMS/вызовами (Room); клик по событию — полные детали: кому (Chat ID), через какого бота (@username), канал, попытки и полный текст отправленного сообщения
- 📊 **Вкладка «Логи»** — INFO/OK/WARN/ERROR, in-memory кольцевой буфер 200 записей
- 📦 **Экспорт/импорт настроек** — JSON backup через SAF, privacy-first (секреты не попадают в файл)
- ⬆️ **Автообновления** — проверка GitHub Releases при запуске (не чаще раза в сутки), ручная кнопка
- 🎨 **Темы** — светлая / тёмная / по системе
- 🧩 **Шаблоны сообщений** — `{sender}`, `{text}`, `{time}`, `{name}`, `{sim}` и другие; кнопки «Проверить» (превью сообщения), «Сохранить» и «Сбросить»
- 🔒 **Пароли прокси** — в EncryptedSharedPreferences, миграция из v0.4.x

---

## 📥 Установка

Скачайте последний **release APK** со страницы [Releases](https://github.com/ozyab09/sms-forwarder/releases).

> ⚠️ Приложение не в Google Play — установите APK вручную (разрешите «Неизвестные источники»).

---

## ⚙️ Быстрый старт (3 шага)

### 1. Создайте бота
Откройте [@BotFather](https://t.me/BotFather) в Telegram → `/newbot` → получите **токен** (вроде `123456789:ABC...`).

### 2. Введите токен в приложении
Откройте SMS Forwarder → вставьте токен → нажмите **«Проверить связь»**.

### 3. Получите Chat ID
Напишите боту `/start` → в приложении нажмите **«Получить мой ID»** → готово!

---

## 🔧 Настройки (вкладка «Настройки»)

| Настройка | Описание |
|-----------|----------|
| **Токен бота** | Обязательно. Из @BotFather. Хранится в EncryptedSharedPreferences. |
| **Chat ID** | Ваш числовой ID. Кнопка «Получить мой ID» заберёт его через getUpdates. |
| **Каналы отправки** | «Без прокси» (всегда) + HTTP/SOCKS5 прокси. Порядок = приоритет каскада. |
| **Прокси-канал** | Тип (HTTP/SOCKS5), хост, порт, логин/пароль (пароль — в EncryptedSharedPreferences). |
| **Переключатели** | Вкл/выкл SMS, вызовы. |
| **Шаблоны сообщений** | Кастомный формат SMS и вызовов с плейсхолдерами `{sender}`/`{text}`/`{time}` и т.д. |
| **Тихие часы** | Интервал (в т.ч. через полночь), когда пересылка не выполняется. |
| **История** | Вкладка с последними событиями (SMS/вызовы), хранятся локально в Room. |
| **Статус** | Запуск/остановка сервиса, счётчик, последние события. |
| **Темы** | Светлая / тёмная / по системе (во вкладке «О приложении»). |
| **Автообновления** | Проверка при запуске (раз в сутки) + кнопка «Проверить обновления» в «О приложении». |

---

## 🛡️ Приватность и безопасность

- **SMS и номера не покидают телефон** — только через ваш бот в Telegram
- **Токен зашифрован** — `EncryptedSharedPreferences` (AES-256)
- **Всё локально** — логи (кольцевой буфер 200 записей) и история событий хранятся только на устройстве и никуда не отправляются
- **Секреты не в бэкапе** — экспорт настроек не включает токен и пароли прокси
- **Нет аналитики, трекеров, рекламы**

---

## 📦 Сборка (для разработчиков)

Требования: **JDK 17**, **Android SDK 34**.

```bash
# Debug APK (для тестов):
./gradlew assembleDebug

# Тесты + линтер:
./gradlew testDebugUnitTest lintDebug

# Release APK (подписанный; секреты через env):
./gradlew assembleRelease
```

---

## 🔄 CI/CD (GitHub Actions)

| Событие | Что происходит |
|---------|----------------|
| Pull Request | Debug APK + тесты + lint (артефакт) |
| Тег `vX.Y.Z` | Подписанный Release APK + **GitHub Release** с changelog |

Версия берётся из тега (`GITHUB_REF_NAME`). `versionCode` = `major*10000 + minor*100 + patch`.

**Секреты репозитория** (Settings → Secrets → Actions):

| Секрет | Назначение |
|--------|-----------|
| `KEYSTORE_BASE64` | Keystore для подписи (base64) |
| `KEYSTORE_PASSWORD` | Пароль keystore |
| `KEY_ALIAS` | Алиас ключа |
| `KEY_PASSWORD` | Пароль ключа |

---

## 🏗️ Архитектура (кратко)

```
UI (MainActivity — тонкий вид) → MainViewModel (StateFlow/SharedFlow)
        │
        ├── Prefs: DataStore (plain) + EncryptedSharedPreferences (секреты)
        ├── ChannelStore / ChannelSender → TelegramClient → Bot API (HTTPS + proxy)
        │
SmsReceiver / CallReceiver / BootReceiver
        │
ForwardService (Foreground, START_STICKY)
        ├── EventHistory (Room) + вкладка «История»
        └── SendQueue + каскадные ретраи
```

---

## 🛡️ Права доступа

`RECEIVE_SMS` · `READ_PHONE_STATE` · `READ_CALL_LOG` · `READ_CONTACTS` ·
`INTERNET` · `FOREGROUND_SERVICE` · `RECEIVE_BOOT_COMPLETED` ·
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

---

## 📁 Структура проекта

```
sms-forwarder/
├── app/src/main/java/com/ozyab/smsforwarder/
│   ├── ui/          # MainActivity (3 tabs), MainViewModel (MVVM), OnboardingActivity
│   ├── receiver/    # SmsReceiver, CallReceiver, BootReceiver
│   ├── service/     # ForwardService (font, queue, retries), SendQueue, EventQueueStore
│   ├── telegram/    # Channel, ChannelStore, ChannelClientFactory, ChannelSender, TelegramClient
│   ├── history/     # Room: EventDao, EventDatabase, EventHistory (вкладка «История»)
│   ├── update/      # UpdateChecker, UpdateManager (GitHub Releases)
│   └── util/        # Prefs, LogStore, ThemeManager, QuietHours, SettingsBackup, TemplateFormatter
├── app/src/main/res/           # layout, strings (ru/en), themes, icons
├── app/src/test/               # unit-тесты (JUnit + Robolectric)
├── gradle/                     # wrapper, libs.versions.toml (version)
├── .github/workflows/build.yml # CI/CD (PR: debug+tests+lint; tag v*: release+GitHub Release)
├── docs/
│   ├── TECH_TASK.md            # полное ТЗ (актуализировано)
│   ├── BRAINSTORM.md           # архив идей
│   └── screenshots/            # app.png
├── ROADMAP.md                  # план работ + карта покрытия тестами
├── AGENTS.md                   # документация для разработчиков
├── CHANGELOG.md
└── README.md
```

---

## 💡 Идеи для дальнейшего развития

| Идея | Описание | Сложность | Статус |
|------|----------|-----------|--------|
| **Экспорт/импорт настроек** | Backup JSON (секреты не пишутся) — уже реализовано | Низкая | ✅ v0.4.22 |
| **Расписание/тихие часы** | Не слать в заданные интервалы | Низкая | ✅ v0.4.23 |
| **Шаблоны сообщений** | `{sender}`, `{text}`, `{time}`, `{contact}` | Низкая | ✅ реализовано |
| **ViewModel + MVVM** | Логика вынесена из MainActivity, состояние переживает поворот | Средняя | ✅ v0.4.24 |
| **Превью шаблонов** | Кнопка «Проверить» показывает, как будет выглядеть SMS/пропущенный вызов | Низкая | ✅ v0.4.29 |
| **Детали события в истории** | Полный текст, Chat ID, бот, канал — по клику на событие | Низкая | ✅ v0.4.30 |
| **Мульти-бот** | Несколько ботов с разными Chat ID (разные получатели) | Средняя | ⏳ |
| **Групповые уведомления** | Telegram-группы/супергруппы (topic_id для форумов) | Низкая | ⏳ |
| **Web UI для логов** | Встроенный веб-сервер (NanoHTTPD) для просмотра логов с ПК | Средняя | ⏳ |
| **Дублирование каналов** | Параллельная отправка в 2+ ботов одновременно | Средняя | ⏳ |
| **Push-уведомления на телефон** | Локальные уведомления при получении SMS/вызова | Низкая | ⏳ |
| **Поиск по логам** | Фильтр по уровню/тексту/времени во вкладке «Логи» | Низкая | ⏳ |
| **Статистика** | Графики: SMS/день, успешность по каналам, latency | Средняя | ⏳ |
| **MQTT / Webhook** | Альтернативные каналы доставки (Home Assistant, n8n) | Высокая | ⏳ |
| **F-Droid публикация** | Автосборка и публикация (требует reproducible builds) | Высокая | ⏳ |
| **Kotlin Multiplatform** | iOS версия (общий core) | Очень высокая | ⏳ |