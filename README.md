# 📱 SMS Forwarder — Android → Telegram

> Пересылает входящие SMS и пропущенные вызовы с Android в Telegram.
> Работает в фоне **без всплывающих уведомлений**, не выгружается системой.
> Поддержка HTTP/SOCKS5 прокси для обхода блокировок.

![android](https://img.shields.io/badge/Android-8.0%2B-green)
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
- 🔄 **Каскадные ретраи** — пробует каналы по порядку, экспоненциальный бэкофф 15с→30с→60с (кап 10 мин), суммарно ~15 мин
- 📊 **Вкладка «Логи»** — INFO/OK/WARN/ERROR, in-memory кольцевой буфер 200 записей
- ⬆️ **Автообновления** — проверка GitHub Releases, скачивание APK в фоне
- 🎨 **Темы** — светлая / тёмная / по системе
- 🛡️ **Фильтры SMS** — белый список, чёрный regex, короткие номера
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
| **Переключатели** | Вкл/выкл SMS, вызовы, фильтр коротких номеров. |
| **Фильтры SMS** | Режим (все / белый список / черный список regex), белый список, regex. |
| **Статус** | Запуск/остановка сервиса, счётчик, последние события. |
| **Темы** | Светлая / тёмная / по системе (во вкладке «О приложении»). |
| **Автообновления** | Кнопка «Проверить обновления» — скачивает APK с GitHub Releases. |

---

## 🛡️ Приватность и безопасность

- **SMS и номера не покидают телефон** — только через ваш бот в Telegram
- **Токен зашифрован** — `EncryptedSharedPreferences` (AES-256)
- **Нет логов** — только последние 10 событий в памяти приложения
- **Нет аналитики, трекеров, рекламы** — полностью локально

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
UI (MainActivity) → EncryptedSharedPreferences
        │
SmsReceiver / CallReceiver / BootReceiver
        │
ForwardService (Foreground, START_STICKY)
        ├── очередь + ретраи
        └── TelegramClient → Bot API (HTTPS + proxy)
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
│   ├── ui/          # MainActivity (настройки + статус)
│   ├── receiver/    # SmsReceiver, CallReceiver, BootReceiver
│   ├── service/     # ForwardService (фон + очередь)
│   ├── telegram/    # TelegramClient, ProxyConfig
│   └── util/        # Prefs, ContactNames
├── app/src/main/res/           # layout, strings, темы, иконки
├── app/src/test/               # юнит-тесты
├── gradle/                     # wrapper, libs.versions.toml
├── .github/workflows/build.yml # CI/CD + SemVer релизы
├── docs/
│   ├── TECH_TASK.md            # полное ТЗ
│   └── screenshots/            # скриншоты (добавить позже)
├── AGENTS.md                   # техническая документация для разработчиков
├── CHANGELOG.md
└── README.md
```

---

## 💡 Идеи для дальнейшего развития

| Идея | Описание | Сложность |
|------|----------|-----------|
| **Мульти-бот** | Поддержка нескольких ботов с разными Chat ID (разные получатели) | Средняя |
| **Групповые уведомления** | Отправка в Telegram-группы/супергруппы (topic_id для форумов) | Низкая |
| **Экспорт/импорт настроек** | Backup JSON (EncryptedSharedPreferences → файл) для переноса на др. телефон | Низкая |
| **Web UI для логов** | Встроенный веб-сервер (NanoHTTPD) для просмотра логов с ПК в локальной сети | Средняя |
| **Расписание/тихие часы** | Не слать в определённое время, только накоплять и слать пачкой | Низкая |
| **Шаблоны сообщений** | Переменные: `{sender}`, `{text}`, `{time}`, `{contact}` для кастомного формата | Низкая |
| **Дублирование каналов** | Параллельная отправка в 2+ Telegram-бота одновременно (не каскадом) | Средняя |
| **Push-уведомления на телефон** | Локальные уведомления (без сети) при получении SMS/вызова | Низкая |
| **Поиск по логам** | Фильтр по уровню/тексту/времени во вкладке «Логи» | Низкая |
| **Статистика** | Графики: SMS/день, успешность по каналам, latency | Средняя |
| **MQTT / Webhook** | Альтернативные каналы доставки (Home Assistant, n8n, свои серверы) | Высокая |
| **F-Droid публикация** | Автосборка и публикация в F-Droid (требует reproducible builds) | Высокая |
| **Kotlin Multiplatform** | iOS версия (общий core: Channel, Sender, Queue, Retry) | Очень высокая |

---

## 📁 Структура проекта

```
sms-forwarder/
├── app/src/main/java/com/ozyab/smsforwarder/
│   ├── ui/          # MainActivity (3 tabs), item_channel.xml
│   ├── receiver/    # SmsReceiver, CallReceiver, BootReceiver
│   ├── service/     # ForwardService (foreground, queue, retries)
│   ├── telegram/    # Channel, ChannelStore, ChannelClientFactory, ChannelSender, TelegramClient
│   ├── update/      # UpdateChecker, UpdateManager (GitHub Releases)
│   └── util/        # Prefs, LogStore, ThemeManager, ContactNames
├── app/src/main/res/           # layout, strings, values-en, themes, icons
├── app/src/test/               # ChannelSenderTest (16 тестов)
├── gradle/                     # wrapper, libs.versions.toml (version)
├── .github/workflows/build.yml # CI/CD (tags v* + workflow_dispatch)
├── docs/
│   ├── TECH_TASK.md            # полное ТЗ
│   └── screenshots/            # app.png
├── AGENTS.md                   # документация для разработчиков
├── CHANGELOG.md
└── README.md
```