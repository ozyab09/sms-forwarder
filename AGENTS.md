# AGENTS.md — Technical Guide for SMS Forwarder

> Этот файл — полная техническая документация для разработчиков и ИИ-агентов.
> README.md — для пользователей (дружелюбный, скриншоты, минимум технических деталей).

---

## 🎯 Project Overview

**SMS Forwarder** — Android app forwarding incoming SMS & missed calls to Telegram via Bot API.
- Min SDK: 26 (Android 8.0), Target: 34 (Android 14)
- Language: Kotlin, Gradle Kotlin DSL
- Architecture: Clean separation — UI, Receivers, Foreground Service, Telegram Client
- Privacy-first: encrypted token storage, no logs, no analytics

---

## 🏗️ Architecture Details

```
┌─────────────────────────────────────────────────────────────────────┐
│  UI Layer (MainActivity)                                            │
│  - Settings: token, chatId, proxy, toggles                          │
│  - Status: start/stop service, counters, event log (last 10)        │
│  - Storage: EncryptedSharedPreferences (secure) + SharedPreferences │
└────────────────────────────┬────────────────────────────────────────┘
                             │ Prefs (LiveData / direct reads)
┌────────────────────────────▼────────────────────────────────────────┐
│  Broadcast Receivers (system-triggered)                             │
│  ├─ SmsReceiver:     SMS_RECEIVED → extract sender/body/date        │
│  ├─ CallReceiver:    PHONE_STATE + CallLog → detect missed only     │
│  └─ BootReceiver:    BOOT_COMPLETED → start ForwardService          │
└────────────────────────────┬────────────────────────────────────────┘
                             │ Intent extras (Event DTOs)
┌────────────────────────────▼────────────────────────────────────────┐
│  ForwardService (Foreground, START_STICKY, type dataSync)           │
│  ├─ EventQueue: ConcurrentLinkedQueue<Event> + persistence (Room?)  │
│  ├─ RetryWorker: exponential backoff (10s → 30s → 1m → 5m cap)     │
│  ├─ NetworkMonitor: ConnectivityManager callback                    │
│  └─ TelegramClient (interface)                                      │
│       └─ BotApiClient (OkHttp + ProxyConfig)                        │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Classes

| Package | Class | Responsibility |
|---------|-------|----------------|
| `ui` | `MainActivity` | Settings UI, service control, onboarding |
| `receiver` | `SmsReceiver` | Catch incoming SMS, parse sender from contacts |
| `receiver` | `CallReceiver` | Track call state, confirm missed via CallLog |
| `receiver` | `BootReceiver` | Auto-start service on boot |
| `service` | `ForwardService` | Foreground service, queue, retries, notifications |
| `telegram` | `TelegramClient` | Bot API sendMessage / getUpdates / getMe |
| `telegram` | `ProxyConfig` | HTTP/SOCKS5 proxy for OkHttp (respects `proxyEnabled`) |
| `util` | `Prefs` | Encrypted (token, proxyPass) + plain prefs |
| `util` | `ContactNames` | Resolve phone number → contact name (cached) |

---

## 🔐 Security Model

| Data | Storage | Encryption |
|------|---------|------------|
| Bot token | `EncryptedSharedPreferences` | AES256-GCM (MasterKey) |
| Proxy password | `EncryptedSharedPreferences` | AES256-GCM |
| Chat ID, proxy host/port, toggles | Plain `SharedPreferences` | None (not secret) |
| Event log (last 10) | In-memory only | Never persisted |

**No** data leaves device except via user's Telegram bot over HTTPS.
**No** crash reporting, analytics, or network calls except Bot API.

---

## 🌐 Proxy Implementation

```kotlin
// ProxyConfig.kt — critical fix applied (commit 5075c74)
fun current(): ProxySettings? {
    if (!Prefs.proxyEnabled) return null  // ← respects UI toggle
    return ProxySettings(...)
}

fun okHttpProxy(s: ProxySettings?): Pair<Proxy?, String?> {
    if (s == null) return null to null    // ← no proxy when disabled
    ...
}

fun httpClient(): Pair<OkHttpClient, String?> {
    val (proxy, err) = okHttpProxy(current())
    if (err != null) return client to err
    if (proxy == null) return clientWithoutProxy to null  // ← direct connection
    return clientWithProxy to null
}
```

**Proxy types supported**: `http`, `socks5`
**Auth**: Basic auth for HTTP proxy (user/pass from prefs)

---

## 📱 Foreground Service Details

| Aspect | Implementation |
|--------|----------------|
| Type | `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (Android 14+) |
| Lifecycle | `START_STICKY` — system restarts if killed |
| Notification | Channel `IMPORTANCE_MIN` (invisible on lock screen) |
| Battery | Requests `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Vendor quirks | Onboarding shows vendor-specific autostart instructions |
| Queue | In-memory `ConcurrentLinkedQueue`, retry with backoff |
| Max retry delay | 5 minutes (exponential: 10s, 30s, 60s, 120s, 300s) |

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

### Test Coverage Areas
- `Prefs` encryption/decryption roundtrip
- `ProxyConfig` null handling when disabled
- `ContactNames` phone normalization + cache
- `TelegramClient` request/response parsing (mocked OkHttp)
- `ForwardService` queue ordering + retry logic

---

## 🔄 CI/CD Pipeline (`.github/workflows/build.yml`)

### Triggers
| Event | Jobs Run |
|-------|----------|
| `pull_request` | `build` (debug APK + test + lint) |
| `push` tags `v*` | `release` (signed APK + GitHub Release) |

### Jobs
```yaml
build:
  runs-on: ubuntu-latest
  steps:
    - checkout@v7.0.1
    - setup-java@v5.7.0 (temurin 17, gradle cache)
    - setup-android@v4.0.1 (gradle cache)
    - assembleDebug
    - testDebugUnitTest + lintDebug
    - upload-artifact@v7 (debug APK)

release:
  runs-on: ubuntu-latest
  if: startsWith(github.ref, 'refs/tags/v')
  steps:
    - checkout@v7.0.1
    - setup-java@v5.7.0
    - setup-android@v4.0.1
    - assembleRelease (signs with KEYSTORE_* secrets)
    - upload-artifact@v7 (release APK)
    - softprops/action-gh-release@v2.6.2 (create Release)
```

### Versioning (SemVer)
- Tags: `v<major>.<minor>.<patch>` (e.g., `v1.2.0`)
- `versionName` = tag without `v`
- `versionCode` = `major*10000 + minor*100 + patch`
- Source: `gradle/libs.versions.toml` (versionMajor/minor/patch) + CI tag

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
# 1. Update version in libs.versions.toml
versionMajor = "1"
versionMinor = "2"
versionPatch = "0"

# 2. Commit + tag
git add gradle/libs.versions.toml
git commit -m "chore: bump version to 1.2.0"
git tag v1.2.0
git push origin main --tags

# 3. GitHub Actions builds release APK + creates Release automatically
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
│   │   │   │   ├── ui/MainActivity.kt
│   │   │   │   ├── receiver/
│   │   │   │   │   ├── SmsReceiver.kt
│   │   │   │   │   ├── CallReceiver.kt
│   │   │   │   │   └── BootReceiver.kt
│   │   │   │   ├── service/ForwardService.kt
│   │   │   │   ├── telegram/
│   │   │   │   │   ├── TelegramClient.kt
│   │   │   │   │   └── ProxyConfig.kt
│   │   │   │   └── util/
│   │   │   │       ├── Prefs.kt
│   │   │   │       └── ContactNames.kt
│   │   │   └── res/
│   │   │       ├── layout/activity_main.xml
│   │   │       ├── values/strings.xml, colors.xml, themes.xml
│   │   │       ├── mipmap-*/ic_launcher*.png  # adaptive icon
│   │   │       └── xml/ (notification channels, backup rules)
│   │   └── test/...               # Unit tests
│   └── proguard-rules.pro
├── gradle/
│   ├── libs.versions.toml         # Version catalog (AGP, Kotlin, deps, app version)
│   └── wrapper/gradle-wrapper.properties
├── .github/workflows/build.yml    # CI/CD
├── docs/
│   ├── TECH_TASK.md               # Full technical specification
│   └── screenshots/               # Placeholder for README screenshots
├── build.gradle.kts               # Root build script
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
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
| Proxy shown in UI test even when disabled | Fixed in `ProxyConfig.current()` — returns null when `!proxyEnabled` (commit 5075c74) |
| Vendor autostart (MIUI, EMUI, OneUI) | Onboarding shows vendor-specific instructions; `START_STICKY` helps but not 100% |
| Android 13+ notification permission | Not requested — channel is `IMPORTANCE_MIN`, user can disable in system settings |
| CallLog permission revoked on some OEMs | Graceful fallback: if CallLog unavailable, treat all ended ringing as missed |
| SMS receiver order | No priority set — works alongside default SMS app; no `READ_SMS` needed for incoming |

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

## 🔗 Useful Links

- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Android Foreground Services](https://developer.android.com/guide/components/foreground-services)
- [OkHttp Proxy Auth](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-proxy-authenticator/)
- [EncryptedSharedPreferences](https://developer.android.com/topic/security/data/encrypted-shared-preferences)
- [SemVer](https://semver.org/)

---

_Updated: 2026-09-09_