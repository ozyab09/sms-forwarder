# 📱 SMS Forwarder — Android → Telegram

> Пересылка входящих SMS и пропущенных вызовов с Android в Telegram.
> Работает в фоне **без всплывающих уведомлений**, не выгружается системой.
> Поддержка **MTProto** и **web-proxy** для Telegram.

![android](https://img.shields.io/badge/Android-8.0%2B-green)
![kotlin](https://img.shields.io/badge/Kotlin-1.9-orange)
![license](https://img.shields.io/badge/license-MIT-lightgrey)

---

## 🚀 Возможности

- 📩 **Пересылка SMS** — мгновенно, с именем отправителя из контактов
- 📵 **Пропущенные вызовы** — только реально не отвеченные
- 🚫 **Без всплывающих уведомлений** — невидимый канал `IMPORTANCE_MIN`
- 🌙 **Работает в фоне** — Foreground Service `START_STICKY`, автостарт после перезагрузки
- 🔒 **Токен в UI** — ввод в приложении, хранение в `EncryptedSharedPreferences`
- 🛰️ **MTProto** — TDLib (последняя версия схемы Telegram) [этап 4]
- 🌐 **Web-proxy** — HTTP / SOCKS5 / MTProto-proxy для обхода блокировок
- 🔄 **Надёжность** — очередь недоставленных с ретраями (backoff до 5 мин)

## 📦 Сборка

Требования: JDK 17, Android SDK 34.

```bash
# Локальная debug-сборка:
./gradlew assembleDebug

# Юнит-тесты + lint:
./gradlew testDebugUnitTest lintDebug

# Release (подписанный; ключ через env KEYSTORE_BASE64/PASSWORD/...):
./gradlew assembleRelease
```

## 🔄 CI/CD (GitHub Actions)

Пайплайн (`.github/workflows/build.yml`) собирает APK и создаёт релизы по **SemVer**:

| Событие | Джоба | Результат |
|---------|-------|-----------|
| push в любую ветку / PR | `build` | debug APK + тесты + lint (артефакт) |
| тег `vX.Y.Z` | `release` | подписанный release APK + **GitHub Release** с changelog |

Теги: `v<major>.<minor>.<patch>` (например `v1.2.0`). Версия берётся из тега
(`GITHUB_REF_NAME`), `versionCode` = `major*10000 + minor*100 + patch`.

Секреты репозитория (Settings → Secrets and variables → Actions):

| Секрет | Назначение |
|--------|-----------|
| `KEYSTORE_BASE64` | keystore для подписи (base64) |
| `KEYSTORE_PASSWORD` | пароль keystore |
| `KEY_ALIAS` | алиас ключа |
| `KEY_PASSWORD` | пароль ключа |

## 🏗️ Архитектура

```
UI (MainActivity) → Prefs (EncryptedSharedPreferences)
        │
SmsReceiver / CallReceiver / BootReceiver
        │
ForwardService (Foreground, START_STICKY)
        ├── очередь + ретраи
        └── TelegramClient
              ├── Bot API (HTTPS + HTTP/SOCKS5 proxy) — лёгкий путь
              └── TDLib (MTProto + HTTP/SOCKS5/MTProto proxy) — этап 4
```

## 📁 Структура

```
sms-forwarder/
├── app/src/main/java/com/ozyab/smsforwarder/
│   ├── ui/          # MainActivity (настройки + статус)
│   ├── receiver/    # SmsReceiver, CallReceiver, BootReceiver
│   ├── service/     # ForwardService (фон + очередь)
│   ├── telegram/    # TelegramClient, ProxyConfig
│   └── util/        # Prefs (шифрованное хранилище), ContactNames
├── app/src/main/res/           # layout, strings, темы
├── app/src/test/               # юнит-тесты
├── gradle/                     # wrapper, libs.versions.toml
├── .github/workflows/build.yml # CI/CD + SemVer релизы (GitHub Actions)
└── docs/TECH_TASK.md           # полное ТЗ
```

## 🛡️ Права доступа

`RECEIVE_SMS` · `READ_PHONE_STATE` · `READ_CALL_LOG` · `READ_CONTACTS` ·
`INTERNET` · `FOREGROUND_SERVICE` · `RECEIVE_BOOT_COMPLETED` ·
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

**Замечание по безопасности:** SMS и номера не покидают устройство иначе как
через Telegram-бота. Токен хранится шифрованно. Лог событий — только в памяти.

## ⚖️ Лицензия

MIT — личный проект.

---

_ТЗ: [`docs/TECH_TASK.md`](docs/TECH_TASK.md) · Сборка: GitHub Actions + SemVer_