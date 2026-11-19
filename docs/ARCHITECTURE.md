# Samsung Guard — архитектура и состояние

Резидентный компаньон для Android 7.1.1 (SM-J500H, root/Magisk):
читает SMS и звонки, решает судьбу SMS через LLM, транслирует в Telegram.

## Железо
- SM-J500H, Android 7.1.1 (SDK 25), ARMv7 (armeabi-v7a, 32-бит)
- 1.42 ГБ RAM, /data 4.7 ГБ, root Magisk 20.1
- adb: `tools/platform-tools/adb`, серийник `fd30c24d`

## Каналы
| Что | Значение |
|---|---|
| Telegram-бот | @Cyber_Miller_bot, токен `0000000000:...` |
| Чат владельца | **000000000** (Igor, @redacted) |
| LLM | `http://192.168.1.55:20128/v1` (OpenAI-совместимый), модель `gemini/gemini-flash-lite-latest` |
| LLM-сеть | доступен только из локальной сети 192.168.1.0/24 (телефон 192.168.1.56) |

## Архитектура (Фаза 1 — Kotlin, работает)

```
SmsReceiver (BroadcastReceiver, goAsync)
   └→ SmsStore (SQLite guard.db)
        └→ Pipeline (single-thread executor, retry ×3)
             ├→ LlmClient.triage()      → Verdict(keep|delete|alert, explanation, severity)
             ├→ TelegramClient.sendSmsAlert()  → инлайн-кнопки Оставить/Удалить
             └→ SmsStore.markHandled()

TelegramPoller (long-poll getUpdates, 50 c)
   └→ callback_query → keep:N / del:N → markHandled / deleteSms
        └→ answerCallbackQuery + editMessageText

CallMonitor (PhoneStateListener)
   └→ RINGING → "Входящий звонок", IDLE+offhook → "Звонок завершён Nм Nс", IDLE+ringing → "Пропущенный"

HealthMonitor (в GuardService, раз в 6 ч + через 30 с)
   └→ battery/temp/storage/RAM/load/SoC → Telegram
```

Ключевые файлы:
- `app/src/main/java/com/cybermiller/guard/GuardService.kt` — foreground service, оркестратор
- `SmsReceiver.kt`, `SmsStore.kt`, `Pipeline.kt`, `LlmClient.kt`
- `TelegramClient.kt`, `TelegramPoller.kt`, `CallMonitor.kt`, `HealthMonitor.kt`
- `MainActivity.kt` — UI: Start/Stop/Demo
- `DemoSms.kt` — 6 тестовых сценариев без SIM

## Сборка

**Локально** (работает, tested 19.11.2026):
- JDK 17 + Android SDK 34 на внешнем диске `/run/media/uzzzver/Main/guardbuild/`
- `JAVA_HOME=... ./gradlew assembleDebug --no-daemon` → `app/build/outputs/apk/debug/app-debug.apk`
- GRADLE_USER_HOME на внешнем диске (внутренний диск забит на 87%)

**На GitHub:** `lagovi/samsung-guard` (public). Workflow пока НЕ запушен:
у gh-токена нет scope `workflow`. Варианты: добавить scope или
`gh auth refresh -s workflow`.

## Метрики (замер 19.11.2026)

| Метрика | Значение |
|---|---|
| APK | 5.7 МБ (debug) |
| **PSS в фоне (только сервис)** | **24.4 МБ** |
| RSS (виртуальный, вкл. общие либы) | 66 МБ |
| Java Heap | 3.9 МБ |
| Native Heap | 3.2 МБ |
| Code (ART/AndroidX/Kotlin) | 8.5 МБ |
| Потоков | 21 |

Цель фазы 2 (Rust musl): **2–5 МБ PSS**.

## Демо-сценарии (`DemoSms.kt`)
`900` код подтверждения → alert; `MEGAFON` реклама → delete; `112` МЧС → keep;
`MOM` личное → keep; `SBERBANK` выписка → keep; `CLUB` спам → delete.

## Что проверено на живом телефоне
- ✅ APK собрался и установился
- ✅ Сервис стартует (foreground, `isForeground=true`)
- ✅ LLM-триаж: keep/delete/alert с осмысленными объяснениями
- ✅ Telegram: сообщения доставлены, инлайн-кнопки приходят
- ✅ Кнопка «Удалить»: `callback data=del:2` → `sms #2 deleted by owner`
- ✅ Бот может редактировать свои сообщения (`editMessageText ok:true`)
- ✅ Health-report ушёл в Telegram
- ✅ Long-poll кнопок работает (HTTP 200, 50 c)
- ✅ CallMonitor стартует

## Ограничения фазы 1
- **Нет SIM** — SMS/звонки пока только демо-режим
- LLM отвечает **~11–29 с** (медленный провайдер) — retry ×3 с backoff 5/10 с
- Иногда провайдер возвращает пустой content → harden: проверка на choices/content
- Нет автозапуска после перезагрузки для production (BootReceiver есть, но
  Android 7 + Doze может убить service — нужен Magisk-демон в фазе 2)
- `usesCleartextTraffic=true` + network_security_config — нужен для локального HTTP

## Бэклог
1. Тест на реальной SIM (владелец вставит карту)
2. GitHub Actions (нужен scope `workflow`)
3. **Фаза 2: Rust** — musl/armv7, static ELF под root, SQLite+TLS, RSS 2–5 МБ,
   автозапуск через Magisk `post-fs-data.sh` / `service.sh`
4. Авто-режим: после отладки LLM получает право удалять без подтверждения
5. Мониторинг Viber — **не начинать без явной просьбы владельца** (см. project-tasks)